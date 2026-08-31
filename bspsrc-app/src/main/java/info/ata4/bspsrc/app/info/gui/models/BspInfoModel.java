package info.ata4.bspsrc.app.info.gui.models;

import info.ata4.bspsrc.app.info.BspFileUtils;
import info.ata4.bspsrc.app.info.gui.data.EmbeddedInfo;
import info.ata4.bspsrc.app.info.gui.data.GameLumpInfo;
import info.ata4.bspsrc.app.info.gui.data.LumpInfo;
import info.ata4.bspsrc.decompiler.modules.BspChecksum;
import info.ata4.bspsrc.decompiler.modules.BspCompileParams;
import info.ata4.bspsrc.decompiler.modules.BspDependencies;
import info.ata4.bspsrc.decompiler.modules.BspProtection;
import info.ata4.bspsrc.decompiler.modules.geom.BrushBounds;
import info.ata4.bspsrc.decompiler.modules.texture.TextureSource;
import info.ata4.bspsrc.decompiler.util.WindingFactory;
import info.ata4.bspsrc.lib.BspFile;
import info.ata4.bspsrc.lib.BspFileReader;
import info.ata4.bspsrc.lib.exceptions.BspException;
import info.ata4.bspsrc.lib.lump.AbstractLump;
import info.ata4.bspsrc.lib.struct.BspData;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public class BspInfoModel {

	private static final Logger L = LogManager.getLogger();

	private final List<Runnable> listeners = new ArrayList<>();

	private BspFile bspFile;
	private BspData bspData;
	private BspCompileParams cparams;
	private BspProtection prot;
	private BspDependencies bspres;

	private Long fileCrc;
	private Long mapCrc;

	private List<LumpInfo> lumps = List.of();
	private List<GameLumpInfo> gameLumps = List.of();
	private List<EmbeddedInfo> embeddedInfos = List.of();

	/**
	 * Loads the specified bsp file.
	 * <p>
	 * Reading a bsp file is expensive - it computes a crc over the whole file, scans it for
	 * protection and enumerates its pakfile - so all of that is done on a background thread.
	 * This model and its listeners are only ever touched on the event dispatch thread, after
	 * reading finished successfully.
	 *
	 * @param filePath the bsp file to read
	 * @param onFinished called on the event dispatch thread once the load finished, with
	 *                   {@code null} if it succeeded or the cause of the failure otherwise
	 */
	public void load(Path filePath, Consumer<Throwable> onFinished) {
		new LoadWorker(filePath, onFinished).execute();
	}

	/**
	 * Reads everything this model exposes from the specified bsp file, without touching any
	 * state. Includes the protection scan, as it is only meaningful together with the rest
	 * of the loaded data.
	 * <p>
	 * This does blocking file io and must therefore <b>not</b> be called on the event
	 * dispatch thread, use {@link #load(Path, Consumer)} instead.
	 */
	private static LoadedBsp read(Path filePath) throws BspException, IOException {
		var bspFile = new BspFile();
		bspFile.load(filePath);

		int lumpSizeSum = bspFile.getLumps().stream()
				.mapToInt(AbstractLump::getLength)
				.sum();

		var lumps = bspFile.getLumps().stream()
				.map(lump -> new LumpInfo(
						lump.getIndex(),
						lump.getName(),
						lump.getLength(),
						(int) Math.round(lump.getLength() * 100.0 / lumpSizeSum),
						lump.getVersion()
				))
				.toList();

		int gameLumpSizeSum = bspFile.getGameLumps().stream()
				.mapToInt(AbstractLump::getLength)
				.sum();

		var gameLumps = bspFile.getGameLumps().stream()
				.map(lump -> new GameLumpInfo(
						lump.getName(),
						lump.getLength(),
						(int) Math.round(lump.getLength() * 100.0 / gameLumpSizeSum),
						lump.getVersion()
				))
				.toList();

		var bspReader = new BspFileReader(bspFile);
		bspReader.loadEntities();

		var windingFactory = WindingFactory.forAppId(bspFile.getAppId());
		var brushBounds = new BrushBounds(windingFactory);

		var bspData = bspReader.getData();
		var cparams = new BspCompileParams(bspReader);

		var texsrc = new TextureSource(bspReader);
		var prot = new BspProtection(bspReader, brushBounds, texsrc, false);
		prot.check();

		var bspres = new BspDependencies(bspReader);

		var checksum = new BspChecksum(bspReader);
		long fileCrc = checksum.getFileCRC();
		long mapCrc = checksum.getMapCRC();

		List<EmbeddedInfo> embeddedInfos = List.of();
		try (ZipFile zip = bspFile.getPakFile().getZipFile()) {
			var files = new ArrayList<EmbeddedInfo>();

			Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
			while (enumeration.hasMoreElements()) {
				ZipArchiveEntry ze = enumeration.nextElement();
				files.add(new EmbeddedInfo(ze.getName(), ze.getSize()));
			}

			embeddedInfos = files;
		} catch (IOException ex) {
			L.warn("Can't read pak", ex);
		}

		return new LoadedBsp(
				bspFile,
				bspData,
				cparams,
				prot,
				bspres,
				fileCrc,
				mapCrc,
				lumps,
				gameLumps,
				embeddedInfos
		);
	}

	/**
	 * Applies a previously read bsp file to this model and notifies all listeners.
	 * <p>
	 * Must be called on the event dispatch thread, as the listeners update swing components.
	 */
	private void apply(LoadedBsp bsp) {
		bspFile = bsp.bspFile();
		bspData = bsp.bspData();
		cparams = bsp.cparams();
		prot = bsp.prot();
		bspres = bsp.bspres();
		fileCrc = bsp.fileCrc();
		mapCrc = bsp.mapCrc();
		lumps = bsp.lumps();
		gameLumps = bsp.gameLumps();
		embeddedInfos = bsp.embeddedInfos();

		listeners.forEach(Runnable::run);
	}

	/**
	 * Extracts the specified lumps into {@code lumpsDst}.
	 * <p>
	 * Blocking io, must not be called on the event dispatch thread.
	 */
	public void extractLumps(Set<Integer> lumpIndices, Path lumpsDst) throws IOException {
		for (int lumpIndex : lumpIndices) {
			var lump = bspFile.getLumps().get(lumpIndex);
			BspFileUtils.extractLump(lump, lumpsDst);
		}
	}

	/**
	 * Extracts the specified game lumps into {@code lumpsDst}.
	 * <p>
	 * Blocking io, must not be called on the event dispatch thread.
	 */
	public void extractGameLumps(Set<Integer> lumpIndices, Path lumpsDst) throws IOException {
		for (int lumpIndex : lumpIndices) {
			var lump = bspFile.getGameLumps().get(lumpIndex);
			BspFileUtils.extractGameLump(lump, lumpsDst);
		}
	}

	/**
	 * Extracts the specified embedded files into {@code filesDst}.
	 * <p>
	 * Blocking io, must not be called on the event dispatch thread.
	 */
	public void extractEmbeddedFiles(Set<Integer> fileIndices, Path filesDst) throws IOException {

		// this is maybe a little bit weird of doing this, but i can't be bothered
		// to change the PakFile api

		var fileNames = new ArrayList<String>();
		try (ZipFile zip = bspFile.getPakFile().getZipFile()) {
			Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
			for (int i = 0; enumeration.hasMoreElements(); i++) {
				ZipArchiveEntry ze = enumeration.nextElement();

				if (fileIndices.contains(i))
					fileNames.add(ze.getName());
			}
		}

		bspFile.getPakFile().unpack(filesDst, fileNames::contains);
	}

	/**
	 * Extracts the pakfile as a raw zip file to {@code filesDst}.
	 * <p>
	 * Blocking io, must not be called on the event dispatch thread.
	 */
	public void extractEmbeddedFilesRaw(Path filesDst) throws IOException {
		bspFile.getPakFile().unpack(filesDst, true);
	}

	public void addListener(Runnable listener) {
		listeners.add(listener);
	}

	public Optional<BspFile> getBspFile() {
		return Optional.ofNullable(bspFile);
	}
	public Optional<BspData> getBspData() {
		return Optional.ofNullable(bspData);
	}
	public Optional<BspCompileParams> getCparams() {
		return Optional.ofNullable(cparams);
	}
	public Optional<BspProtection> getProt() {
		return Optional.ofNullable(prot);
	}
	public Optional<BspDependencies> getBspres() {
		return Optional.ofNullable(bspres);
	}
	public Optional<Long> getFileCrc() {
		return Optional.ofNullable(fileCrc);
	}
	public Optional<Long> getMapCrc() {
		return Optional.ofNullable(mapCrc);
	}
	public List<LumpInfo> getLumps() {
		return lumps;
	}
	public List<GameLumpInfo> getGameLumps() {
		return gameLumps;
	}
	public List<EmbeddedInfo> getEmbeddedInfos() {
		return embeddedInfos;
	}

	private class LoadWorker extends SwingWorker<LoadedBsp, Void> {

		private final Path filePath;
		private final Consumer<Throwable> onFinished;

		private LoadWorker(Path filePath, Consumer<Throwable> onFinished) {
			this.filePath = requireNonNull(filePath);
			this.onFinished = requireNonNull(onFinished);
		}

		@Override
		protected LoadedBsp doInBackground() throws BspException, IOException {
			return read(filePath);
		}

		@Override
		protected void done() {
			LoadedBsp bsp = null;
			Throwable failureCause = null;
			try {
				bsp = get();
			} catch (InterruptedException e) {
				failureCause = e;
			} catch (ExecutionException e) {
				failureCause = e.getCause();
			}

			// only update the model once everything could be read, so a failed load leaves
			// the previously loaded bsp file in place instead of a half updated model
			if (bsp != null)
				apply(bsp);

			onFinished.accept(failureCause);
		}
	}

	/**
	 * Everything {@link #read(Path)} extracts from a bsp file, handed over to the event
	 * dispatch thread in one piece.
	 */
	private record LoadedBsp(
			BspFile bspFile,
			BspData bspData,
			BspCompileParams cparams,
			BspProtection prot,
			BspDependencies bspres,
			long fileCrc,
			long mapCrc,
			List<LumpInfo> lumps,
			List<GameLumpInfo> gameLumps,
			List<EmbeddedInfo> embeddedInfos
	) {}
}
