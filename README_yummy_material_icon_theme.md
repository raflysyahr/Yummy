YUMMY — MATERIAL ICON THEME INTEGRATION

Baseline
- Repository: https://github.com/raflysyahr/Yummy
- Branch: feature/storage-provider-system
- Baseline commit: 5a8b24cad2df6d2f9b6c65ca60f8983e0ae189b8
- Material Icon Theme release: 5.40.0

What changed
- Replaces File Manager file and folder glyphs with the official Material Icon Theme catalog.
- Uses the official mapping rules for exact file names, file extensions, folder names,
  expanded folders, named root folders, and default file/folder icons.
- Includes all 1,256 icon definitions as 64x64 transparent PNGs rendered from the
  original SVG artwork. This avoids adding an SVG runtime dependency to the Android app.
- Adds material-style vector icons for File Manager controls: search, overflow, storage
  rail, disclosure arrows, selection state, create, sort, copy, move, delete, and save.
- Reuses the existing File Manager sidebar and adapter dimensions; no layout XML,
  storage-provider, file-operation, navigation, or editor behavior is intentionally changed.

Source provenance
- Mapping catalog comes from extension/dist/material-icons.json in the uploaded
  material-icon-theme-5.40.0.vsix package.
- SVG artwork was cross-checked against the uploaded vscode-material-icon-theme-main.zip.
  907 source SVGs were byte-identical to the VSIX release; matching files were used
  from the source archive. For 1 differing and 348 absent source paths, the versioned
  VSIX artwork was retained to keep the icon catalog consistent with its mapping.
- Material Icon Theme license is included at:
  app/src/main/assets/material-icon-theme/LICENSE.txt

Install
Run from the Yummy repository root:
  unzip -o yummy_material_icon_theme.zip -d .

Then build:
  ./gradlew assembleDebug

Implementation notes
- app/src/main/java/com/termux/app/filemanager/ui/FileManagerController.java
  is the complete replacement controller with icon rendering wired in.
- app/src/main/java/com/termux/app/filemanager/ui/FileManagerIconResolver.java
  performs theme-name and extension mapping and caches decoded bitmaps.
- app/src/main/assets/material-icon-theme/ contains mapping.json and all icon PNG assets.
- app/src/main/res/drawable/ic_fm_*.xml contains only the File Manager control icons;
  file/folder artwork is loaded from Material Icon Theme assets.

Validation
- All 1,256 icon definitions rendered successfully as 96x96 transparent PNGs.
- All file, folder, expanded-folder, and root-folder mapping values resolve to an included PNG.
- All drawable XML files parse.
- Controller icon references and resolver method calls were checked statically.
- A full Android/Gradle build and on-device UI test were not run because the full
  Yummy repository/Gradle project was not available in this workspace.


Explorer icon sizing refinement
- File/folder and disclosure artwork is rendered at 16dp, with a 4dp gap after the
  disclosure and a 6dp gap between the file/folder icon and its filename.
- The storage rail artwork is constrained to 24dp. Search/overflow controls use 18dp
  artwork while preserving their original touch targets.
- Action-bar and selection-bar compound icons are constrained to 16dp with a 5dp
  icon-to-label gap. Existing row height, typography, colors, and interactions remain
  unchanged; the secondary metadata line is retained.
- This is a sizing/spacing alignment inspired by VS Code Explorer, adapted to the
  existing two-line Android rows; it is not a pixel-identical reproduction of desktop
  VS Code's 22px single-line tree rows.

## File/folder list row cleanup

- Removed the secondary metadata line under file and folder names (type labels such as Folder/JSON/JavaScript and file size).
- Removed ListView row separators so file/folder rows have no top/bottom divider lines.
- Reduced file/folder row icon slots from 46dp to 38dp and removed vertical row padding, producing a more compact row height.
- Storage-location rows retain their description text; only file/folder metadata labels are hidden.

## Sidebar color palette update

Only colors were changed; dimensions, spacing, row heights, and layout were left untouched.

- Main background: `#13141A`
- Sidebar: `#13151B`
- Panel: `#17191F`
- Toolbar: `#1D1F26`
- Selected item: `#292C35`
- Border/divider palette: `#252831`
- Muted icon/text: `#969EA7`
- Secondary text: `#5B6068`
- Primary text: `#E8EAED`

Storage rail icons are monochrome silver to match the supplied palette.
