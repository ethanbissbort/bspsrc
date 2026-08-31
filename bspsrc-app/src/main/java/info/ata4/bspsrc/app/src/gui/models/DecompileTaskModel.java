package info.ata4.bspsrc.app.src.gui.models;

import info.ata4.bspsrc.app.src.gui.data.ErrorNotification;
import info.ata4.bspsrc.app.src.gui.data.Task;
import info.ata4.bspsrc.app.util.log.Log4jUtil;
import info.ata4.bspsrc.decompiler.BspFileEntry;
import info.ata4.bspsrc.decompiler.BspSource;
import info.ata4.bspsrc.decompiler.BspSourceConfig;

import javax.swing.*;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static info.ata4.bspsrc.app.util.ErrorMessageUtil.decompileExceptionToMessage;
import static java.util.Objects.requireNonNull;

public class DecompileTaskModel {

	private final DecompileWorker worker;

	private final List<Consumer<State>> stateListeners = new ArrayList<>();
	private final List<Consumer<Integer>> taskChangeListeners = new ArrayList<>();
	private final List<Consumer<ErrorNotification>> notificationsListeners = new ArrayList<>();

	private State state = new State.Running();
	private final List<Task> tasks;
	private final List<Document> taskLogs;

	public DecompileTaskModel(BspSourceConfig config, List<BspFileEntry> entries) {
		this.worker = new DecompileWorker(config, entries);
		this.tasks = entries.stream()
				.map(bspFileEntry -> new Task(
						Task.State.PENDING,
						bspFileEntry.getBspFile()
				))
				.collect(Collectors.toCollection(ArrayList::new));
		this.taskLogs = Stream.generate(PlainDocument::new)
				.limit(this.tasks.size())
				.collect(Collectors.toList());

		this.worker.execute();
	}

	public void addStateListener(Consumer<State> listener) {
		stateListeners.add(listener);
	}

	public void addTaskUpdateListener(Consumer<Integer> listener) {
		taskChangeListeners.add(listener);
	}

	public void addNotificationListener(Consumer<ErrorNotification> listener) {
		notificationsListeners.add(listener);
	}

	public List<Task> getTasks() {
		return Collections.unmodifiableList(tasks);
	}

	private void updateTask(int index, Function<Task, Task> function) {
		var newTask = function.apply(tasks.get(index));
		tasks.set(index, newTask);
		taskChangeListeners.forEach(consumer -> consumer.accept(index));
	}

	public Document getTaskLog(int index) {
		return taskLogs.get(index);
	}

	public State getState() {
		return state;
	}

	private void setState(State state) {
		this.state = requireNonNull(state);
		stateListeners.forEach(consumer -> consumer.accept(state));
	}

	/**
	 * Cancels the running decompilation.
	 * <p>
	 * Entries that haven't started yet are dropped, entries that are being decompiled right now
	 * stop at their next task boundary, which can take a moment for a big map. Entries that
	 * already finished keep their vmf files, half written ones are deleted.
	 * <p>
	 * Has to be called on the EDT and does nothing if the decompilation already ended.
	 */
	public void cancel() {
		this.worker.cancelDecompiling();
	}

	public void close() {
		cancel();
	}

	private class DecompileWorker extends SwingWorker<Void, BspSource.Signal> {

		private final List<BspFileEntry> entries;
		/**
		 * Created up front, so {@link #cancelDecompiling()} can reach it even if the worker
		 * hasn't started running yet.
		 */
		private final BspSource bspSource;

		private DecompileWorker(BspSourceConfig config, List<BspFileEntry> entries) {
			this.entries = List.copyOf(entries);
			this.bspSource = new BspSource(requireNonNull(config), this.entries);
		}

		@Override
		protected Void doInBackground() throws InterruptedException {
			try (var scope0 = Log4jUtil.configureDecompilationLogFileAppender(bspSource.getEntryUuids(), entries);
			     var scope1 = Log4jUtil.configureDecompilationDocumentAppenders(bspSource.getEntryUuids(), taskLogs)) {
				bspSource.run(this::publish);
			}
			return null;
		}

		private void cancelDecompiling() {
			// stops the decompiler itself. Without this, its executor would keep working through
			// the remaining entries after we stopped listening
			bspSource.cancel();
			// interrupts doInBackground, so it stops waiting for the signals that are still missing
			this.cancel(true);
		}

		@Override
		protected void process(List<BspSource.Signal> signals) {
			for (BspSource.Signal signal : signals) {
				int taskIndex;
				Task.State state;

                switch (signal) {
                    case BspSource.Signal.TaskStarted taskSig -> {
                        taskIndex = taskSig.index();
                        state = Task.State.RUNNING;
                    }
                    case BspSource.Signal.TaskFinished taskSig -> {
                        taskIndex = taskSig.index();
                        state = Task.State.FINISHED;
                    }
                    case BspSource.Signal.TaskFailed taskSig -> {
                        taskIndex = taskSig.index();
                        state = Task.State.FAILED;
                    }
                    case BspSource.Signal.TaskCancelled taskSig -> {
                        taskIndex = taskSig.index();
                        // the entry was abandoned, it didn't fail, so it must not show up as an error
                        state = Task.State.CANCELLED;
                    }
                    case null, default -> throw new RuntimeException("Not reachable");
                }

				updateTask(taskIndex, task -> new Task(state, task.bspFile()));
				if (signal instanceof BspSource.Signal.TaskFailed(var index, var exception)) {
					var notification = new ErrorNotification(
							decompileExceptionToMessage(exception),
                            index
					);
					notificationsListeners.forEach(consumer -> consumer.accept(notification));
				}
			}
		}

		@Override
		protected void done() {
			if (isCancelled()) {
				// cancelling is a clean end, not a failure. Without this the model would stay
				// 'Running' forever and any ui bound to it would keep offering to cancel
				setState(new DecompileTaskModel.State.Finished(null));
				return;
			}

			Throwable failureCause = null;
			try {
				get();
			} catch (InterruptedException e) {
				failureCause = e;
			} catch (ExecutionException e) {
				failureCause = e.getCause();
			}

			setState(new DecompileTaskModel.State.Finished(failureCause));
		}
	}

	public sealed interface State {
		record Running() implements State {}
		record Finished(Throwable t) implements State {}
	}
}
