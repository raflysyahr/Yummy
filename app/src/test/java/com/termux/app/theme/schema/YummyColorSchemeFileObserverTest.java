package com.termux.app.theme.schema;

import android.os.FileObserver;
import android.os.Looper;

import com.termux.app.theme.storage.ThemeColorSchemeStorage;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.Shadows;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** Exercises directory-event filtering and reload behavior without relying on native filesystem events. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class YummyColorSchemeFileObserverTest {
    private static final long EVENT_TIMEOUT_SECONDS = 6L;

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void reloadsLatestDocumentAfterColorSchemeFileChanges() throws Exception {
        File directory = temporaryFolder.newFolder("theme-package");
        File file = new File(directory, ThemeColorSchemeStorage.FILE_NAME);
        write(file, "{\"version\":3,\"marker\":\"initial\"}");

        BlockingQueue<ObserverEvent> events = new LinkedBlockingQueue<>();
        YummyColorSchemeFileObserver observer = newObserver(file, events);
        try {
            observer.startWatching();
            ObserverEvent initial = awaitEvent(events);
            assertNotNull(initial.document);
            assertEquals("initial", initial.document.getString("marker"));

            write(file, "{\"version\":3,\"marker\":\"updated\"}");
            observer.handleDirectoryEvent(FileObserver.CLOSE_WRITE, ThemeColorSchemeStorage.FILE_NAME);
            ObserverEvent updated = awaitEvent(events);
            assertNotNull(updated.document);
            assertEquals("updated", updated.document.getString("marker"));
            assertNull(updated.failureCode);
        } finally {
            observer.close();
        }
    }

    @Test
    public void malformedDocumentReportsFailureWithoutDeliveringReplacement() throws Exception {
        File directory = temporaryFolder.newFolder("malformed-package");
        File file = new File(directory, ThemeColorSchemeStorage.FILE_NAME);
        write(file, "{\"version\":3,\"marker\":\"accepted\"}");

        BlockingQueue<ObserverEvent> events = new LinkedBlockingQueue<>();
        YummyColorSchemeFileObserver observer = newObserver(file, events);
        try {
            observer.startWatching();
            ObserverEvent initial = awaitEvent(events);
            assertEquals("accepted", initial.document.getString("marker"));

            write(file, "{ malformed json");
            observer.handleDirectoryEvent(FileObserver.CLOSE_WRITE, ThemeColorSchemeStorage.FILE_NAME);
            ObserverEvent rejected = awaitEvent(events);
            assertNull(rejected.document);
            assertNotNull(rejected.failureCode);
        } finally {
            observer.close();
        }
    }

    @Test
    public void ignoresChangesToUnrelatedFilesInThemeDirectory() throws Exception {
        File directory = temporaryFolder.newFolder("unrelated-package");
        File file = new File(directory, ThemeColorSchemeStorage.FILE_NAME);
        write(file, "{\"version\":3,\"marker\":\"initial\"}");

        BlockingQueue<ObserverEvent> events = new LinkedBlockingQueue<>();
        YummyColorSchemeFileObserver observer = newObserver(file, events);
        try {
            observer.startWatching();
            awaitEvent(events);
            write(new File(directory, "ui-schema.json"), "{}");
            observer.handleDirectoryEvent(FileObserver.CLOSE_WRITE, "ui-schema.json");
            assertNull(awaitOptionalEvent(events, 700L));
        } finally {
            observer.close();
        }
    }

    private static YummyColorSchemeFileObserver newObserver(
        File file, BlockingQueue<ObserverEvent> events
    ) {
        YummyColorSchemeFileObserver observer = new YummyColorSchemeFileObserver(
            file, new ThemeColorSchemeStorage());
        observer.setListener(new YummyColorSchemeFileObserver.Listener() {
            @Override public void onReload(JSONObject document) {
                events.offer(ObserverEvent.loaded(document));
            }

            @Override public void onReloadFailed(String code, String message) {
                events.offer(ObserverEvent.failed(code));
            }
        });
        return observer;
    }

    private static ObserverEvent awaitEvent(BlockingQueue<ObserverEvent> events)
        throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EVENT_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ObserverEvent event = events.poll(25L, TimeUnit.MILLISECONDS);
            if (event != null) return event;
        }
        throw new AssertionError("Timed out waiting for Color Scheme observer event");
    }

    private static ObserverEvent awaitOptionalEvent(
        BlockingQueue<ObserverEvent> events, long timeoutMillis
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ObserverEvent event = events.poll(20L, TimeUnit.MILLISECONDS);
            if (event != null) return event;
        }
        return null;
    }

    private static void write(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class ObserverEvent {
        final JSONObject document;
        final String failureCode;

        private ObserverEvent(JSONObject document, String failureCode) {
            this.document = document;
            this.failureCode = failureCode;
        }

        static ObserverEvent loaded(JSONObject document) {
            return new ObserverEvent(document, null);
        }

        static ObserverEvent failed(String code) {
            return new ObserverEvent(null, code);
        }
    }
}
