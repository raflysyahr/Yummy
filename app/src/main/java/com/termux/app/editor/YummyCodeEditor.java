package com.termux.app.editor;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;

import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * CodeEditor Yummy. Satu perubahan perilaku: menekan Enter pada baris yang kosong atau hanya
 * berisi spasi tidak mewariskan indentasi ke baris baru. Pada baris yang berisi kode, auto-indent
 * Sora/TextMate (termasuk onEnterRules untuk def, if, for, dan seterusnya) bekerja seperti biasa.
 */
public class YummyCodeEditor extends CodeEditor {

    private static final String TAG = "YummyEditor";

    static {
        // Pemeriksaan sekali: kalau signature method berbeda di versi pustaka, override ini
        // tidak akan terpanggil, dan kita perlu tahu.
        try {
            CodeEditor.class.getMethod("commitText", CharSequence.class, boolean.class);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "CodeEditor.commitText(CharSequence, boolean) tidak ditemukan; "
                + "aturan Enter pada baris kosong tidak aktif.");
        }
    }

    public YummyCodeEditor(Context context) {
        super(context);
    }

    public YummyCodeEditor(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public YummyCodeEditor(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public YummyCodeEditor(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void commitText(CharSequence text, boolean applyAutoIndent) {
        if (applyAutoIndent && text != null && text.length() == 1 && text.charAt(0) == '\n'
            && isCurrentLineBlank()) {
            // Baris kosong memutus warisan indentasi: sisipkan baris baru polos.
            super.commitText(text, false);
            return;
        }
        super.commitText(text, applyAutoIndent);
    }

    private boolean isCurrentLineBlank() {
        try {
            Cursor cursor = getCursor();
            if (cursor.isSelected()) return false;
            String line = getText().getLineString(cursor.getLeftLine());
            return line.trim().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
