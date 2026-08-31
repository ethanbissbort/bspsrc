# BSPSource

BSPSource is a map decompiler for [Source engine](http://developer.valvesoftware.com/wiki/Source) maps, written in Java.
It decompiles .bsp map files back to .vmf files that can be loaded in Hammer, Valve's official level editor.

BSPSource is based on a reengineered version of [VMEX 0.98g](http://www.bagthorpe.org/bob/cofrdrbob/vmex.html) by Rof, which is no longer developed and lacks support for newer Source engine games.

## Running BSPSource

1. Go to the [releases page](https://github.com/ata4/bspsrc/releases) and, depending on your operating system, download either `bspsrc-linux.zip` or `bspsrc-windows.zip`. Alternatively, `bspsrc-jar-only.zip` works on any system that has Java 24+ installed and properly configured.
2. Extract the contents of the zip file to a new folder.
3. To launch bspsrc/bspinfo, run the corresponding file: `bspsrc.bat`/`bspinfo.bat` on Windows, or `bspsrc.sh`/`bspinfo.sh` on Linux.

### Included tools

* **bspsrc** — the decompiler itself. Launches the graphical interface when started without arguments, and acts as a command line tool when given any; run it with `--help` for the full list of options.
* **bspinfo** — a graphical viewer for a map's header, lumps, entities, embedded files and anti-decompiling protection.
* **bspunprotect** — a small command line tool that recovers the entity lump of a map protected with [BSPProtect](https://developer.valvesoftware.com/wiki/BSPProtect). Usage: `bspunprotect <BSP file> [key]`. It defaults to the standard BSPProtect key, so the key argument is only needed for a map using a custom one. The .bsp itself is left untouched: the decrypted entities are written alongside it as a `<mapname>_l_0.lmp` lump file, which the engine and bspsrc both read as an override. Decompile the map afterwards to get the restored entities.

## Frequently asked Questions

* **Can you support _\<game\>_:** Generally speaking, most Source engine games should work with BSPSource out of the box, even though they might not be explicitly "supported". If you run into any problems or errors with any particular game/map, feel free to open an [Issue](https://github.com/ata4/bspsrc/issues).\
Games based on the older goldsrc or on the newer Source 2 engine, are not supported. Their map format wildly differs from the one the Source engine uses. 
* **Help, all the textures are gray/models are errors:** Most of the time this is due to the materials/models not existing in the base game but rather being embedded in the bsp file. This can frequently happen with workshop maps or with maps using csgo's automcombine prop feature. To make the materials/models visible in hammer, you need to extract the embedded files by checking `Extract embedded files` in the decompiler and then move the contents of the extracted material/model folders into your games material/model folder.

## Limitations and known bugs

* Some internal entities that are entirely consumed by vbsp can't be perfectly restored. This includes the following entities:
	* [func_instance](http://developer.valvesoftware.com/wiki/func_instance)
	* [func_instance_parms](http://developer.valvesoftware.com/wiki/func_instance_parms)
	* [func_instance_origin](http://developer.valvesoftware.com/wiki/func_instance_origin)
	* [func_viscluster](http://developer.valvesoftware.com/wiki/func_viscluster)
	* [info_no_dynamic_shadow](http://developer.valvesoftware.com/wiki/info_no_dynamic_shadow) 

## Improvements and changes compared to VMEX 0.98g

* Support for more and newer Source engine games up to Dota 2.
* Support for new entity types:
	* [func_areaportal](http://developer.valvesoftware.com/wiki/func_areaportal)
	* [func_areaportalwindow](http://developer.valvesoftware.com/wiki/func_areaportalwindow)
	* [func_occluder](http://developer.valvesoftware.com/wiki/func_occluder)
	* [info_lighting](http://developer.valvesoftware.com/wiki/info_lighting)
* Support for the tools/blocklight texture.
* Support for compressed and big-endian encoded maps (XBox 360, PS3)
* Decompiles VMEX maps flagged with protection and at least detects other anti-decompiling methods.
* Improved support for [prop_static](http://developer.valvesoftware.com/wiki/prop_static) and [info_overlay](http://developer.valvesoftware.com/wiki/info_overlay).
* Improved console output.
* New graphical user interface with output window.
* New command line interface.
* New integrated pakfile extractor.
* Numerous bug fixes.
* Open source.