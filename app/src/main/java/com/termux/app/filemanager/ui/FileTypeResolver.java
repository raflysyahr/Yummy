package com.termux.app.filemanager.ui;

import androidx.annotation.NonNull;

import java.util.Locale;

/** Resolves a compact, text-based file badge and MIME hint from a file name. */
public final class FileTypeResolver {

    public enum Kind {
        FOLDER, PYTHON, JAVASCRIPT, TYPESCRIPT, JAVA, KOTLIN, PHP, HTML, CSS,
        JSON, XML, SHELL, MARKDOWN, SQL, YAML, IMAGE, VIDEO, AUDIO, PDF,
        ARCHIVE, FONT, APK, TEXT, DOCUMENT, DATABASE, CONFIG, CODE, UNKNOWN
    }

    public static final class Descriptor {
        private final Kind kind;
        private final String badge;
        private final int color;
        private final String label;
        private final String mimeType;
        private final boolean textEditable;

        private Descriptor(Kind kind, String badge, int color, String label,
                           String mimeType, boolean textEditable) {
            this.kind = kind;
            this.badge = badge;
            this.color = color;
            this.label = label;
            this.mimeType = mimeType;
            this.textEditable = textEditable;
        }

        public Kind getKind() { return kind; }
        @NonNull public String getBadge() { return badge; }
        public int getColor() { return color; }
        @NonNull public String getLabel() { return label; }
        @NonNull public String getMimeType() { return mimeType; }
        public boolean isTextEditable() { return textEditable; }
    }

    private FileTypeResolver() { }

    @NonNull
    public static Descriptor resolve(@NonNull String name, boolean directory, String providerMimeType) {
        if (directory) return d(Kind.FOLDER, "DIR", 0xFF64748B, "Folder", "vnd.android.document/directory", false);

        String lower = name.toLowerCase(Locale.ROOT);
        String ext = extension(lower);

        // Well-known extensionless files and dotfiles must be checked before the extension.
        if ("dockerfile".equals(lower)) return d(Kind.CONFIG, "DKR", 0xFF2496ED, "Dockerfile", "text/plain", true);
        if ("makefile".equals(lower) || "gnumakefile".equals(lower)) return d(Kind.CONFIG, "MK", 0xFF64748B, "Makefile", "text/plain", true);
        if (".gitignore".equals(lower) || ".gitattributes".equals(lower) || ".editorconfig".equals(lower)) {
            return d(Kind.CONFIG, "GIT", 0xFFE06C75, "Git/configuration", "text/plain", true);
        }
        if (".env".equals(lower) || lower.startsWith(".env.")) return d(Kind.CONFIG, "ENV", 0xFFE5A50A, "Environment file", "text/plain", true);
        if ("package.json".equals(lower) || "composer.json".equals(lower) || "tsconfig.json".equals(lower)) {
            return d(Kind.JSON, "JSON", 0xFFB6A36B, "JSON configuration", "application/json", true);
        }
        if ("build.gradle".equals(lower) || "settings.gradle".equals(lower) || "gradle.properties".equals(lower)) {
            return d(Kind.CONFIG, "GRD", 0xFF2D9CDB, "Gradle configuration", "text/plain", true);
        }

        switch (ext) {
            case "py": return d(Kind.PYTHON, "PY", 0xFF3776AB, "Python", "text/x-python", true);
            case "js": case "mjs": case "cjs": return d(Kind.JAVASCRIPT, "JS", 0xFFF0DB4F, "JavaScript", "text/javascript", true);
            case "jsx": return d(Kind.JAVASCRIPT, "JSX", 0xFF61DAFB, "React JavaScript", "text/javascript", true);
            case "ts": case "mts": case "cts": return d(Kind.TYPESCRIPT, "TS", 0xFF3178C6, "TypeScript", "text/typescript", true);
            case "tsx": return d(Kind.TYPESCRIPT, "TSX", 0xFF3178C6, "React TypeScript", "text/typescript", true);
            case "java": return d(Kind.JAVA, "JAVA", 0xFFEA8C32, "Java", "text/x-java-source", true);
            case "kt": case "kts": return d(Kind.KOTLIN, "KT", 0xFF9867F0, "Kotlin", "text/x-kotlin", true);
            case "php": return d(Kind.PHP, "PHP", 0xFF777BB4, "PHP", "application/x-httpd-php", true);
            case "html": case "htm": return d(Kind.HTML, "HTML", 0xFFE34F26, "HTML", "text/html", true);
            case "css": case "scss": case "sass": case "less": return d(Kind.CSS, "CSS", 0xFF2965F1, "Stylesheet", "text/css", true);
            case "json": case "jsonc": return d(Kind.JSON, "JSON", 0xFFB6A36B, "JSON", "application/json", true);
            case "xml": case "xhtml": return d(Kind.XML, "XML", 0xFFE37933, "XML", "application/xml", true);
            case "svg": return d(Kind.XML, "SVG", 0xFFFFB13B, "SVG image", "image/svg+xml", true);
            case "sh": case "bash": case "zsh": case "fish": return d(Kind.SHELL, "SH", 0xFF4EAA25, "Shell script", "text/x-shellscript", true);
            case "md": case "markdown": case "mdown": return d(Kind.MARKDOWN, "MD", 0xFF519ABA, "Markdown", "text/markdown", true);
            case "sql": return d(Kind.SQL, "SQL", 0xFFE38C00, "SQL", "application/sql", true);
            case "yml": case "yaml": return d(Kind.YAML, "YML", 0xFFCB171E, "YAML", "application/yaml", true);
            case "toml": case "ini": case "conf": case "cfg": case "properties": return d(Kind.CONFIG, "CFG", 0xFF8B8FA3, "Configuration", "text/plain", true);
            case "c": case "h": case "cc": case "cpp": case "hpp": case "rs": case "go": case "swift": case "dart": case "lua": case "rb": case "r": return d(Kind.CODE, ext.toUpperCase(Locale.ROOT), 0xFF3B82F6, "Source code", "text/plain", true);
            case "txt": case "log": case "csv": case "tsv": return d(Kind.TEXT, ext.toUpperCase(Locale.ROOT), 0xFF94A3B8, "Text file", "text/plain", true);
            case "db": case "sqlite": case "sqlite3": return d(Kind.DATABASE, "DB", 0xFF14B8A6, "Database", "application/octet-stream", false);
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "bmp": case "heic": case "avif": return d(Kind.IMAGE, "IMG", 0xFFB56CE2, "Image", mimeFromProvider(providerMimeType, "image/*"), false);
            case "mp4": case "mkv": case "mov": case "webm": case "avi": case "3gp": return d(Kind.VIDEO, "VID", 0xFFE8793C, "Video", mimeFromProvider(providerMimeType, "video/*"), false);
            case "mp3": case "wav": case "ogg": case "m4a": case "flac": case "aac": return d(Kind.AUDIO, "AUD", 0xFF22A06B, "Audio", mimeFromProvider(providerMimeType, "audio/*"), false);
            case "pdf": return d(Kind.PDF, "PDF", 0xFFE64B4B, "PDF document", "application/pdf", false);
            case "zip": case "rar": case "7z": case "tar": case "gz": case "bz2": case "xz": case "tgz": return d(Kind.ARCHIVE, "ZIP", 0xFFB7791F, "Archive", "application/octet-stream", false);
            case "ttf": case "otf": case "woff": case "woff2": return d(Kind.FONT, "FONT", 0xFF8B5CF6, "Font", "application/octet-stream", false);
            case "apk": case "apks": case "xapk": return d(Kind.APK, "APK", 0xFF3DDC84, "Android package", "application/vnd.android.package-archive", false);
            case "doc": case "docx": case "odt": case "rtf": return d(Kind.DOCUMENT, "DOC", 0xFF2B579A, "Document", "application/octet-stream", false);
            case "xls": case "xlsx": case "ods": return d(Kind.DOCUMENT, "XLS", 0xFF217346, "Spreadsheet", "application/octet-stream", false);
            case "ppt": case "pptx": case "odp": return d(Kind.DOCUMENT, "PPT", 0xFFD24726, "Presentation", "application/octet-stream", false);
            default:
                if (providerMimeType != null && providerMimeType.startsWith("text/")) {
                    return d(Kind.TEXT, "TXT", 0xFF94A3B8, "Text file", providerMimeType, true);
                }
                return d(Kind.UNKNOWN, "FILE", 0xFF64748B, "File", mimeFromProvider(providerMimeType, "application/octet-stream"), false);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
    }

    private static String mimeFromProvider(String mime, String fallback) {
        return mime == null || mime.trim().isEmpty() || "application/octet-stream".equals(mime)
            ? fallback : mime;
    }

    private static Descriptor d(Kind kind, String badge, int color, String label,
                                String mime, boolean textEditable) {
        return new Descriptor(kind, badge, color, label, mime, textEditable);
    }
}
