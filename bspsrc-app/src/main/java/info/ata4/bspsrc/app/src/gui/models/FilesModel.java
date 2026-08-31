package info.ata4.bspsrc.app.src.gui.models;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class FilesModel {

	private final List<Path> bspPaths = new ArrayList<>();
	private final Set<Listener> onChangeListeners = new HashSet<>();

	public List<Path> getBspPaths() {
		return Collections.unmodifiableList(bspPaths);
	}

	public void addListener(Listener listener) {
		onChangeListeners.add(listener);
	}

	public void addEntries(Collection<Path> bspPaths) {
		if (bspPaths.isEmpty())
			return;

		// Skip paths that are already in the list, as well as duplicates within the added
		// collection itself. The same bsp being present twice would result in two decompile
		// tasks writing into the same vmf file at the same time, corrupting it.
		var knownPaths = new HashSet<Path>();
		for (Path bspPath : this.bspPaths)
			knownPaths.add(canonicalPath(bspPath));

		var newBspPaths = new ArrayList<Path>();
		for (Path bspPath : bspPaths) {
			if (knownPaths.add(canonicalPath(bspPath)))
				newBspPaths.add(bspPath);
		}

		if (newBspPaths.isEmpty())
			return;

		int minIndex = this.bspPaths.size();
		this.bspPaths.addAll(newBspPaths);
		int maxIndex = this.bspPaths.size() - 1;

		onChangeListeners.forEach(listener -> listener.added(minIndex, maxIndex));
	}

	public void removeEntries(int[] selectedIndices) {
		Arrays.stream(selectedIndices)
				.boxed()
				.sorted(Comparator.reverseOrder())
				.forEachOrdered(i -> {
					bspPaths.remove((int) i);
					onChangeListeners.forEach(listener -> listener.removed(i, i));
				});
	}

	public void removeAllEntries() {
		int maxIndex = bspPaths.size() - 1;
		bspPaths.clear();

		onChangeListeners.forEach(listener -> listener.removed(0, maxIndex));
	}

	/**
	 * Resolves a path into a canonical form, to compare paths by the file they identify rather
	 * than by how they happen to be spelled.
	 * <p>
	 * {@link Path#toRealPath} is preferred, because it also resolves symlinks and, on case
	 * insensitive file systems, the actual case of the file name. It requires the file to exist
	 * though, so for files that don't, we fall back to a purely lexical absolute path.
	 */
	private static Path canonicalPath(Path path) {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	public interface Listener {
		void added(int minIndex, int maxIndex);
		void removed(int minIndex, int maxIndex);
	}
}
