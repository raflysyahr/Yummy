package com.termux.app.editor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.TextRange;
import io.github.rosemoe.sora.text.UndoManager;

/**
 * Durable JSON codec for the Sora Editor 0.24.4 UndoManager.
 *
 * <p>Sora exposes UndoManager through Content, but its action stack and stack pointer are
 * intentionally private. This adapter reads/writes that pinned implementation through
 * reflection. The JSON format is owned by Yummy and is deliberately independent from Android
 * Parcel; Parcel is a process/bundle transport format, not a durable on-disk format.</p>
 *
 * <p>This class is only the serialization layer. Session scheduling, per-tab ownership and disk
 * persistence are integrated in later batches.</p>
 */
public final class SoraUndoHistoryCodec {

    public static final int SCHEMA_VERSION = 1;
    public static final String SORA_VERSION = "0.24.4";

    private static final String FORMAT = "yummy-sora-undo-history";
    private static final int MAX_ACTIONS = 100_000;
    private static final int MAX_NESTING_DEPTH = 64;

    private SoraUndoHistoryCodec() {
    }

    /**
     * Export the current UndoManager into a versioned JSON object.
     * The document text hash binds this history to the exact buffer state at export time.
     */
    @NonNull
    public static JSONObject encode(@NonNull Content content) throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("content == null");
        }

        try {
            UndoManager manager = content.getUndoManager();
            if (manager == null) {
                throw new IOException("Sora Content has no UndoManager");
            }

            // Do not persist a half-finished action. Content notifications normally arrive after
            // Sora has finalized these temporary fields; if not, the caller can retry at the next
            // checkpoint instead of writing an incomplete stack.
            if (readBoolean(manager, "ignoreModification")
                    || readBoolean(manager, "replaceMark")
                    || readObject(manager, "insertAction") != null) {
                throw new IOException("Sora UndoManager is in the middle of an edit");
            }

            @SuppressWarnings("unchecked")
            List<UndoManager.ContentAction> actions =
                    (List<UndoManager.ContentAction>) readObject(manager, "actionStack");
            if (actions == null || actions.size() > MAX_ACTIONS) {
                throw new IOException("Sora UndoManager action stack is invalid or too large");
            }

            int stackPointer = readInt(manager, "stackPointer");
            int maxStackSize = readInt(manager, "maxStackSize");
            if (stackPointer < 0 || stackPointer > actions.size() || maxStackSize <= 0) {
                throw new IOException("Sora UndoManager pointer or stack size is invalid");
            }

            JSONObject result = new JSONObject();
            result.put("format", FORMAT);
            result.put("schemaVersion", SCHEMA_VERSION);
            result.put("soraVersion", SORA_VERSION);
            result.put("textSha256", sha256(content.toString()));
            result.put("maxStackSize", maxStackSize);
            result.put("stackPointer", stackPointer);
            result.put("undoEnabled", readBoolean(manager, "undoEnabled"));
            result.put("forceNewMultiAction", readBoolean(manager, "forceNewMultiAction"));
            result.put("memorizedCursorRange",
                    encodeRange((TextRange) readObject(manager, "memorizedCursorRange")));

            JSONArray serializedActions = new JSONArray();
            for (UndoManager.ContentAction action : actions) {
                serializedActions.put(encodeAction(action, 0));
            }
            result.put("actions", serializedActions);
            return result;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not encode Sora UndoManager history", error);
        }
    }

    /**
     * Restore history only when the snapshot is valid and belongs to the exact current text.
     * A failed restore leaves the Content's existing UndoManager untouched.
     */
    public static boolean restore(@NonNull Content content, @Nullable JSONObject snapshot) {
        if (content == null || snapshot == null) {
            return false;
        }

        try {
            if (!FORMAT.equals(snapshot.optString("format", ""))
                    || snapshot.optInt("schemaVersion", -1) != SCHEMA_VERSION
                    || !SORA_VERSION.equals(snapshot.optString("soraVersion", ""))) {
                return false;
            }

            String storedTextHash = snapshot.optString("textSha256", "");
            if (storedTextHash.isEmpty() || !storedTextHash.equals(sha256(content.toString()))) {
                return false;
            }

            JSONArray serializedActions = snapshot.getJSONArray("actions");
            if (serializedActions.length() > MAX_ACTIONS) {
                return false;
            }

            int stackPointer = snapshot.getInt("stackPointer");
            int maxStackSize = snapshot.getInt("maxStackSize");
            boolean undoEnabled = snapshot.getBoolean("undoEnabled");
            boolean forceNewMultiAction = snapshot.optBoolean("forceNewMultiAction", true);
            TextRange memorizedCursorRange = decodeRange(
                    snapshot.optJSONObject("memorizedCursorRange"));
            if (maxStackSize <= 0 || maxStackSize > MAX_ACTIONS
                    || stackPointer < 0 || stackPointer > serializedActions.length()) {
                return false;
            }

            UndoManager restoredManager = newUndoManager();
            @SuppressWarnings("unchecked")
            List<UndoManager.ContentAction> actions =
                    (List<UndoManager.ContentAction>) readObject(restoredManager, "actionStack");
            if (actions == null) {
                return false;
            }

            for (int i = 0; i < serializedActions.length(); i++) {
                JSONObject actionJson = serializedActions.getJSONObject(i);
                actions.add(decodeAction(actionJson, 0));
            }

            writeInt(restoredManager, "maxStackSize", maxStackSize);
            writeInt(restoredManager, "stackPointer", stackPointer);
            writeBoolean(restoredManager, "undoEnabled", undoEnabled);
            writeBoolean(restoredManager, "forceNewMultiAction", forceNewMultiAction);
            writeDeclaredField(restoredManager, "memorizedCursorRange", memorizedCursorRange);

            // All validation and decoding is complete. Swap the manager only at the end so a
            // malformed or incompatible snapshot cannot partially damage the live Content.
            content.setUndoManager(restoredManager);
            return true;
        } catch (Exception error) {
            android.util.Log.w("YummyUndoHistory",
                    "Ignoring incompatible or damaged Sora Undo history", error);
            return false;
        }
    }

    @NonNull
    private static JSONObject encodeAction(@NonNull UndoManager.ContentAction action, int depth)
            throws Exception {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException("Sora Undo action nesting is too deep");
        }

        String type = actionType(action);
        JSONObject json = new JSONObject();
        json.put("type", type);
        json.put("cursor", encodeRange((TextRange) readPublicField(action, "cursor")));

        switch (type) {
            case "InsertAction":
            case "DeleteAction":
                json.put("startLine", readPublicInt(action, "startLine"));
                json.put("startColumn", readPublicInt(action, "startColumn"));
                json.put("endLine", readPublicInt(action, "endLine"));
                json.put("endColumn", readPublicInt(action, "endColumn"));
                Object text = readPublicField(action, "text");
                json.put("text", text == null ? "" : text.toString());
                json.put("createTime", readPublicLong(action, "createTime"));
                break;
            case "ReplaceAction":
                json.put("insert", encodeAction(
                        (UndoManager.ContentAction) readPublicField(action, "insert"), depth + 1));
                json.put("delete", encodeAction(
                        (UndoManager.ContentAction) readPublicField(action, "delete"), depth + 1));
                break;
            case "MultiAction":
                @SuppressWarnings("unchecked")
                List<UndoManager.ContentAction> nested =
                        (List<UndoManager.ContentAction>) readDeclaredField(action, "_actions");
                if (nested == null || nested.size() > MAX_ACTIONS) {
                    throw new IOException("Sora MultiAction contains an invalid action list");
                }
                JSONArray children = new JSONArray();
                for (UndoManager.ContentAction child : nested) {
                    children.put(encodeAction(child, depth + 1));
                }
                json.put("actions", children);
                break;
            default:
                throw new IOException("Unsupported Sora action type: " + type);
        }
        return json;
    }

    @NonNull
    private static UndoManager.ContentAction decodeAction(@NonNull JSONObject json, int depth)
            throws Exception {
        if (depth > MAX_NESTING_DEPTH) {
            throw new IOException("Sora Undo action nesting is too deep");
        }

        String type = json.getString("type");
        UndoManager.ContentAction action;
        switch (type) {
            case "InsertAction":
                action = new UndoManager.InsertAction();
                decodeTextAction(action, json);
                break;
            case "DeleteAction":
                action = new UndoManager.DeleteAction();
                decodeTextAction(action, json);
                break;
            case "ReplaceAction": {
                UndoManager.ReplaceAction replace = new UndoManager.ReplaceAction();
                replace.insert = (UndoManager.InsertAction) requireTextAction(
                        decodeAction(json.getJSONObject("insert"), depth + 1),
                        UndoManager.InsertAction.class);
                replace.delete = (UndoManager.DeleteAction) requireTextAction(
                        decodeAction(json.getJSONObject("delete"), depth + 1),
                        UndoManager.DeleteAction.class);
                action = replace;
                break;
            }
            case "MultiAction": {
                UndoManager.MultiAction multi = new UndoManager.MultiAction();
                JSONArray children = json.getJSONArray("actions");
                if (children.length() > MAX_ACTIONS) {
                    throw new IOException("Sora MultiAction contains too many child actions");
                }
                @SuppressWarnings("unchecked")
                List<UndoManager.ContentAction> nested =
                        (List<UndoManager.ContentAction>) readDeclaredField(multi, "_actions");
                for (int i = 0; i < children.length(); i++) {
                    nested.add(decodeAction(children.getJSONObject(i), depth + 1));
                }
                action = multi;
                break;
            }
            default:
                throw new IOException("Unknown Sora action type: " + type);
        }

        writePublicField(action, "cursor", decodeRange(json.optJSONObject("cursor")));
        return action;
    }

    private static void decodeTextAction(UndoManager.ContentAction action, JSONObject json)
            throws Exception {
        int startLine = json.getInt("startLine");
        int startColumn = json.getInt("startColumn");
        int endLine = json.getInt("endLine");
        int endColumn = json.getInt("endColumn");
        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            throw new IOException("Sora action contains a negative text position");
        }
        writePublicInt(action, "startLine", startLine);
        writePublicInt(action, "startColumn", startColumn);
        writePublicInt(action, "endLine", endLine);
        writePublicInt(action, "endColumn", endColumn);
        writePublicField(action, "text", json.getString("text"));
        writePublicLong(action, "createTime", json.optLong("createTime", 0L));
    }

    @NonNull
    private static UndoManager.ContentAction requireTextAction(
            @NonNull UndoManager.ContentAction action,
            @NonNull Class<? extends UndoManager.ContentAction> expected) throws IOException {
        if (!expected.isInstance(action)) {
            throw new IOException("Unexpected nested Sora action type");
        }
        return action;
    }

    @Nullable
    private static JSONObject encodeRange(@Nullable TextRange range) throws JSONException {
        if (range == null) {
            return null;
        }
        JSONObject json = new JSONObject();
        json.put("start", encodePosition(range.getStart()));
        json.put("end", encodePosition(range.getEnd()));
        return json;
    }

    @NonNull
    private static JSONObject encodePosition(@NonNull CharPosition position) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("line", position.line);
        json.put("column", position.column);
        json.put("index", position.index);
        return json;
    }

    @Nullable
    private static TextRange decodeRange(@Nullable JSONObject json) throws JSONException {
        if (json == null) {
            return null;
        }
        return new TextRange(
                decodePosition(json.getJSONObject("start")),
                decodePosition(json.getJSONObject("end")));
    }

    @NonNull
    private static CharPosition decodePosition(@NonNull JSONObject json) throws JSONException {
        int line = json.getInt("line");
        int column = json.getInt("column");
        int index = json.getInt("index");
        if (line < 0 || column < 0 || index < -1) {
            throw new JSONException("Invalid Sora cursor position");
        }
        return new CharPosition(line, column, index);
    }

    @NonNull
    private static String actionType(@NonNull UndoManager.ContentAction action) throws IOException {
        if (action.getClass() == UndoManager.InsertAction.class) return "InsertAction";
        if (action.getClass() == UndoManager.DeleteAction.class) return "DeleteAction";
        if (action.getClass() == UndoManager.ReplaceAction.class) return "ReplaceAction";
        if (action.getClass() == UndoManager.MultiAction.class) return "MultiAction";
        throw new IOException("Unsupported Sora action class: " + action.getClass().getName());
    }

    @NonNull
    private static UndoManager newUndoManager() throws Exception {
        Constructor<UndoManager> constructor = UndoManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    @Nullable
    private static Object readObject(@NonNull Object target, @NonNull String name) throws Exception {
        return readDeclaredField(target, name);
    }

    @Nullable
    private static Object readDeclaredField(@NonNull Object target, @NonNull String name)
            throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Nullable
    private static Object readPublicField(@NonNull Object target, @NonNull String name)
            throws Exception {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }

    private static int readInt(@NonNull Object target, @NonNull String name) throws Exception {
        return ((Number) readDeclaredField(target, name)).intValue();
    }

    private static boolean readBoolean(@NonNull Object target, @NonNull String name)
            throws Exception {
        return (Boolean) readDeclaredField(target, name);
    }

    private static int readPublicInt(@NonNull Object target, @NonNull String name)
            throws Exception {
        return ((Number) readPublicField(target, name)).intValue();
    }

    private static long readPublicLong(@NonNull Object target, @NonNull String name)
            throws Exception {
        return ((Number) readPublicField(target, name)).longValue();
    }

    private static void writeInt(@NonNull Object target, @NonNull String name, int value)
            throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void writeBoolean(@NonNull Object target, @NonNull String name, boolean value)
            throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void writeDeclaredField(@NonNull Object target, @NonNull String name,
                                           @Nullable Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void writePublicInt(@NonNull Object target, @NonNull String name, int value)
            throws Exception {
        target.getClass().getField(name).setInt(target, value);
    }

    private static void writePublicLong(@NonNull Object target, @NonNull String name, long value)
            throws Exception {
        target.getClass().getField(name).setLong(target, value);
    }

    private static void writePublicField(@NonNull Object target, @NonNull String name,
                                         @Nullable Object value) throws Exception {
        target.getClass().getField(name).set(target, value);
    }

    @NonNull
    private static Field findField(@NonNull Class<?> type, @NonNull String name)
            throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    @NonNull
    private static String sha256(@NonNull String text) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(value & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }
}
