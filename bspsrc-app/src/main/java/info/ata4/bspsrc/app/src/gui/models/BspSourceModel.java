package info.ata4.bspsrc.app.src.gui.models;

import info.ata4.bspsrc.app.src.ObservableBspSourceConfig;
import info.ata4.bspsrc.decompiler.BspFileEntry;
import info.ata4.bspsrc.decompiler.BspSourceConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

public class BspSourceModel {

	private final ObservableBspSourceConfig config = new ObservableBspSourceConfig(new BspSourceConfig());
	private final Set<Consumer<DecompileTaskModel>> onDecompileTaskListener = new HashSet<>();

	/**
	 * The decompilation that is currently running, {@code null} if none is. Only touched on
	 * the EDT.
	 */
	private DecompileTaskModel activeDecompileTask;

	public void addOnDecompileTask(Consumer<DecompileTaskModel> onDecompileTask) {
		onDecompileTaskListener.add(requireNonNull(onDecompileTask));
	}

	public ObservableBspSourceConfig getConfig() {
		return config;
	}

	public void setDefaults() {
		config.setConfig(new BspSourceConfig());
	}

	public void decompile(List<BspFileEntry> entries) {
		if (entries.isEmpty())
			return;

		var c = config.getCopy(); // copy config so no modifications can happen afterward

		var decompileTaskModel = new DecompileTaskModel(c, entries);
		activeDecompileTask = decompileTaskModel;
		decompileTaskModel.addStateListener(state -> {
			if (state instanceof DecompileTaskModel.State.Finished && activeDecompileTask == decompileTaskModel)
				activeDecompileTask = null;
		});

		onDecompileTaskListener.forEach(consumer -> consumer.accept(decompileTaskModel));
	}

	/**
	 * @return the decompilation that is currently running, if there is one
	 */
	public Optional<DecompileTaskModel> getActiveDecompileTask() {
		return Optional.ofNullable(activeDecompileTask);
	}

	/**
	 * Cancels the currently running decompilation, if there is one.
	 * <p>
	 * Entries that haven't started yet are dropped, entries that are being decompiled right now
	 * stop at their next task boundary. Already finished entries keep their vmf files.
	 */
	public void cancelActiveDecompileTask() {
		var task = activeDecompileTask;
		if (task != null)
			task.cancel();
	}
}
