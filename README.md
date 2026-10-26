# qupath-extension-tracking-overview

Adds a **tracking** custom overview to QuPath's viewer overview. It records which regions of the image
have been shown in the viewer, and at which magnification, and draws the outline of each explored region
on top of the overview thumbnail (one colour per magnification range).

**Requires a QuPath build with the overview layer and customization hooks**
On stock QuPath the extension logs a warning and does nothing.

For now, I would approach this by creating a new local branch with the required additions:

```
# Create a separate branch for testing the overview hooks
git switch -c test-overview-hooks

# Fetch the zindy branch
git remote add zindy https://github.com/zindy/qupath.git
git fetch zindy

# Merge the overview hooks into your new branch
git merge zindy/overview-hooks
```

Also add a `include-extra` file [as explained here](https://github.com/qupath/qupath-extension-template#2-create-an-include-extra-file) at the root of your qupath folder and with the following content (you can add more than one extension):

```
[includeBuild]
../qupath-extension-tracking-overview

[dependencies]
io.github.qupath:qupath-extension-tracking-overview
```

and run (in the qupath folder)

```
gradlew run
```

## Use
* Right-click the overview and choose `Custom overview > Tracking` (per viewer; `None` goes back to the standard overview).
  The submenu is only shown when at least one extension offers a custom overview.
* Choosing `Tracking` starts tracking straight away. The overview's context menu then has:
  * `Show track map` - show or hide the outlines (tracking carries on while hidden)
  * `Reset track map` - start again
* Moving to another image starts a new map. Resizing the overview (Alt + mouse wheel, or the size menu) rescales the existing one.
* Only one custom overview is active per viewer, so choosing another one (e.g. the heatmap) stops tracking.

## Magnification ranges (Need to come up with some sensible values)
| Magnification | Outline colour |
|---|---|
| >= 12x | green |
| >= 5.12x | yellow |
| >= 2.8x | red |
| >= 1.38x | orange |

A pixel records the highest magnification at which it has been seen.

## License
This is just a proof of concept, you're free to use it however you like.
You can treat the contents of *this repository only* as being under [the Unlicense](https://unlicense.org) (except for the Gradle wrapper, which has its own license included).

If you use it to create a new QuPath extension, I'd strongly encourage you to select a suitable open-source license for the extension.

Note that *QuPath itself* is available under the GPL, so you do have to abide by those terms: see https://github.com/qupath/qupath for more.