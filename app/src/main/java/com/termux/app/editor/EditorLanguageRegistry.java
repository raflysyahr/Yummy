package com.termux.app.editor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a document filename to a TextMate grammar registered in
 * assets/textmate/languages.json. The registry intentionally contains only
 * languages for which this app bundles a grammar; everything else is Plain Text.
 */
public final class EditorLanguageRegistry {

    public static final class Language {
        public final String id;
        public final String displayName;
        public final String scopeName;

        private Language(String id, String displayName, String scopeName) {
            this.id = id;
            this.displayName = displayName;
            this.scopeName = scopeName;
        }
    }

    private static final Language PLAIN_TEXT =
        new Language("plaintext", "Plain Text", "text.plain");

    private static final Map<String, Language> EXTENSIONS;
    private static final Map<String, Language> SPECIAL_NAMES;

    static {
        Map<String, Language> extensions = new HashMap<>();
        Map<String, Language> names = new HashMap<>();

        Language bat = language("bat", "Batch", "source.batchfile");
        add(extensions, bat, "bat", "cmd");

        Language clojure = language("clojure", "Clojure", "source.clojure");
        add(extensions, clojure, "clj", "cljs", "cljc", "edn");

        Language coffee = language("coffeescript", "CoffeeScript", "source.coffee");
        add(extensions, coffee, "coffee", "litcoffee");

        Language c = language("c", "C", "source.c");
        add(extensions, c, "c", "h");

        Language cpp = language("cpp", "C++", "source.cpp");
        add(extensions, cpp, "cc", "cpp", "cxx", "hh", "hpp", "hxx", "ipp");

        Language cuda = language("cuda-cpp", "CUDA C++", "source.cuda-cpp");
        add(extensions, cuda, "cu", "cuh");

        Language csharp = language("csharp", "C#", "source.cs");
        add(extensions, csharp, "cs", "csx");

        Language css = language("css", "CSS", "source.css");
        add(extensions, css, "css");

        Language dart = language("dart", "Dart", "source.dart");
        add(extensions, dart, "dart");

        Language diff = language("diff", "Diff", "source.diff");
        add(extensions, diff, "diff", "patch");

        Language docker = language("dockerfile", "Dockerfile", "source.dockerfile");
        names.put("dockerfile", docker);
        names.put("containerfile", docker);
        add(extensions, docker, "dockerfile");

        Language dotenv = language("dotenv", "Dotenv", "source.dotenv");
        names.put(".env", dotenv);
        add(extensions, dotenv, "env");

        Language fsharp = language("fsharp", "F#", "source.fsharp");
        add(extensions, fsharp, "fs", "fsi", "fsx", "fsscript");

        Language go = language("go", "Go", "source.go");
        add(extensions, go, "go");

        Language groovy = language("groovy", "Groovy", "source.groovy");
        add(extensions, groovy, "groovy", "gradle", "gvy", "gy", "gsh");

        Language handlebars = language("handlebars", "Handlebars", "text.html.handlebars");
        add(extensions, handlebars, "hbs", "handlebars", "mustache");

        Language hlsl = language("hlsl", "HLSL", "source.hlsl");
        add(extensions, hlsl, "hlsl", "fx", "fxh");

        Language html = language("html", "HTML", "text.html.basic");
        add(extensions, html, "html", "htm", "xhtml");

        Language jikespg = language("jikespg", "JikesPG", "source.jikespg");
        add(extensions, jikespg, "jsg", "jikespg");

        Language ini = language("ini", "INI", "source.ini");
        add(extensions, ini, "ini", "cfg", "properties", "conf");

        Language java = language("java", "Java", "source.java");
        add(extensions, java, "java");

        Language javascript = language("javascript", "JavaScript", "source.js");
        add(extensions, javascript, "js", "mjs", "cjs");

        Language javascriptReact = language("javascriptreact", "JavaScript React", "source.js.jsx");
        add(extensions, javascriptReact, "jsx");

        Language json = language("json", "JSON", "source.json");
        add(extensions, json, "json");

        Language jsonc = language("jsonc", "JSON with Comments", "source.json.comments");
        add(extensions, jsonc, "jsonc");

        Language jsonl = language("jsonl", "JSON Lines", "source.json.lines");
        add(extensions, jsonl, "jsonl", "ndjson");

        Language julia = language("julia", "Julia", "source.julia");
        add(extensions, julia, "jl");

        Language latex = language("latex", "LaTeX", "text.tex.latex");
        add(extensions, latex, "tex", "ltx");

        Language bibtex = language("bibtex", "BibTeX", "text.bibtex");
        add(extensions, bibtex, "bib", "bibtex");

        Language less = language("less", "Less", "source.css.less");
        add(extensions, less, "less");

        Language lua = language("lua", "Lua", "source.lua");
        add(extensions, lua, "lua");

        Language make = language("makefile", "Makefile", "source.makefile");
        names.put("makefile", make);
        names.put("gnumakefile", make);
        names.put("bsdmakefile", make);
        add(extensions, make, "mk", "mak");

        Language markdown = language("markdown", "Markdown", "text.html.markdown");
        add(extensions, markdown, "md", "markdown", "mdown", "mkdn");

        Language objectiveC = language("objective-c", "Objective-C", "source.objc");
        add(extensions, objectiveC, "m");

        Language objectiveCpp = language("objective-cpp", "Objective-C++", "source.objcpp");
        add(extensions, objectiveCpp, "mm");

        Language perl = language("perl", "Perl", "source.perl");
        add(extensions, perl, "pl", "pm", "t", "pod");

        Language raku = language("raku", "Raku", "source.perl.6");
        add(extensions, raku, "raku", "rakumod", "p6", "pl6");

        Language php = language("php", "PHP", "source.php");
        add(extensions, php, "php", "phtml", "php3", "php4", "php5", "phps");

        Language powershell = language("powershell", "PowerShell", "source.powershell");
        add(extensions, powershell, "ps1", "psm1", "psd1");

        Language pug = language("pug", "Pug", "text.pug");
        add(extensions, pug, "pug", "jade");

        Language python = language("python", "Python", "source.python");
        add(extensions, python, "py", "pyw", "pyi", "pyx", "pxd", "pxi");

        Language r = language("r", "R", "source.r");
        add(extensions, r, "r", "rmd");

        Language razor = language("razor", "Razor", "text.html.cshtml");
        add(extensions, razor, "razor", "cshtml");

        Language rst = language("restructuredtext", "reStructuredText", "source.rst");
        add(extensions, rst, "rst");

        Language ruby = language("ruby", "Ruby", "source.ruby");
        add(extensions, ruby, "rb", "rake", "gemspec");
        names.put("gemfile", ruby);
        names.put("rakefile", ruby);

        Language rust = language("rust", "Rust", "source.rust");
        add(extensions, rust, "rs");

        Language scss = language("scss", "SCSS", "source.css.scss");
        add(extensions, scss, "scss");

        Language shaderlab = language("shaderlab", "ShaderLab", "source.shaderlab");
        add(extensions, shaderlab, "shader", "shaderlab", "compute");

        Language shell = language("shellscript", "Shell Script", "source.shell");
        add(extensions, shell, "sh", "bash", "zsh", "ksh", "command");
        names.put(".bashrc", shell);
        names.put(".zshrc", shell);
        names.put(".profile", shell);
        names.put(".bash_profile", shell);

        Language sql = language("sql", "SQL", "source.sql");
        add(extensions, sql, "sql");

        Language swift = language("swift", "Swift", "source.swift");
        add(extensions, swift, "swift");

        Language typescript = language("typescript", "TypeScript", "source.ts");
        add(extensions, typescript, "ts", "mts", "cts");

        Language typescriptReact = language("typescriptreact", "TypeScript React", "source.tsx");
        add(extensions, typescriptReact, "tsx");

        Language vb = language("vb", "Visual Basic", "source.asp.vb.net");
        add(extensions, vb, "vb", "vbs");

        Language xml = language("xml", "XML", "text.xml");
        add(extensions, xml, "xml", "xsd", "xaml", "svg", "wsdl");

        Language xsl = language("xsl", "XSL", "text.xml.xsl");
        add(extensions, xsl, "xsl", "xslt");

        Language yaml = language("yaml", "YAML", "source.yaml");
        add(extensions, yaml, "yaml", "yml");

        // Common Git metadata files use the bundled Ignore grammar.
        Language ignore = language("ignore", "Ignore", "source.ignore");
        names.put(".gitignore", ignore);
        names.put(".dockerignore", ignore);
        names.put(".npmignore", ignore);
        names.put(".eslintignore", ignore);
        names.put(".prettierignore", ignore);

        EXTENSIONS = Collections.unmodifiableMap(extensions);
        SPECIAL_NAMES = Collections.unmodifiableMap(names);
    }

    private EditorLanguageRegistry() { }

    private static Language language(String id, String displayName, String scopeName) {
        return new Language(id, displayName, scopeName);
    }

    private static void add(Map<String, Language> map, Language language, String... extensions) {
        for (String extension : extensions) map.put(extension, language);
    }

    @NonNull
    public static Language detect(@Nullable String filename) {
        if (filename == null || filename.trim().isEmpty()) return PLAIN_TEXT;
        String name = filename.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);

        Language special = SPECIAL_NAMES.get(name);
        if (special != null) return special;

        // Common Dockerfile variants, e.g. Dockerfile.dev and Containerfile.prod.
        if (name.startsWith("dockerfile.") || name.startsWith("containerfile.")) {
            return language("dockerfile", "Dockerfile", "source.dockerfile");
        }

        // Dotenv files such as .env.local and .env.production.
        if (name.startsWith(".env.")) return language("dotenv", "Dotenv", "source.dotenv");

        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return PLAIN_TEXT;
        String extension = name.substring(dot + 1);
        Language result = EXTENSIONS.get(extension);
        return result == null ? PLAIN_TEXT : result;
    }

}
