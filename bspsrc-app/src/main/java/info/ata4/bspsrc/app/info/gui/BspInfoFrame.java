package info.ata4.bspsrc.app.info.gui;

import com.formdev.flatlaf.extras.components.FlatTabbedPane;
import info.ata4.bspsrc.app.info.gui.models.BspInfoModel;
import info.ata4.bspsrc.app.info.gui.panel.*;
import info.ata4.bspsrc.app.util.log.Log4jUtil;
import info.ata4.bspsrc.app.util.log.plugins.DialogAppender;
import info.ata4.bspsrc.app.util.swing.FileExtensionFilter;
import info.ata4.bspsrc.decompiler.BspSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static info.ata4.bspsrc.app.info.gui.Util.wrapWithAlign;
import static info.ata4.bspsrc.app.util.swing.GridBagConstraintsBuilder.Anchor.FIRST_LINE_START;
import static info.ata4.bspsrc.app.util.swing.GridBagConstraintsBuilder.Anchor.PAGE_START;
import static info.ata4.bspsrc.app.util.swing.GridBagConstraintsBuilder.Fill.HORIZONTAL;
import static info.ata4.bspsrc.app.util.swing.GridBagConstraintsBuilder.Fill.NONE;
import static java.util.Objects.requireNonNull;
import static javax.swing.BorderFactory.createCompoundBorder;

public class BspInfoFrame extends JFrame {

	private static final Logger L = LogManager.getLogger();

	public static final String NAME = "BSPInfo";
	public static final String VERSION = BspSource.VERSION;

	private static final String STATUS_IDLE = "Ready";

	private final BspInfoModel model;

	private final JFileChooser fileChooser = new JFileChooser();
	private final JFileChooser lumpDstChooser = new JFileChooser();
	private final JFileChooser embeddedFileDstChooser = new JFileChooser();
	private final JFileChooser embeddedRawDstChooser = new JFileChooser();

	private final FlatTabbedPane tabbedPane = new FlatTabbedPane();
	private final GeneralPanel generalPanel = new GeneralPanel();
	private final LumpsPanel lumpsPanel = new LumpsPanel(this::extractLumps);
	private final GameLumpsPanel gameLumpsPanel = new GameLumpsPanel(this::extractGameLumps);
	private final EntitiesPanel entitiesPanel = new EntitiesPanel();
	private final DependenciesPanel dependenciesPanel = new DependenciesPanel();
	private final EmbeddedPanel embeddedPanel = new EmbeddedPanel(this::extractFiles, this::extractFilesRaw);
	private final ProtectionPanel protectionPanel = new ProtectionPanel();

	private final JMenuItem menuItemOpenFile = new JMenuItem("Open");
	private final JLabel lblStatus = new JLabel(STATUS_IDLE);
	private final JProgressBar prgBusy = new JProgressBar();

	/**
	 * Whether a background task is currently running. Only ever read and written on the
	 * event dispatch thread.
	 */
	private boolean busy = false;


	public BspInfoFrame(BspInfoModel model) {
		this.model = model;
		model.addListener(this::onChanges);

		initErrorDialog();

		fileChooser.setFileFilter(new FileExtensionFilter("Source engine map file", "bsp"));
		lumpDstChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		embeddedFileDstChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		embeddedRawDstChooser.setAcceptAllFileFilterUsed(false);
		embeddedRawDstChooser.setFileFilter(new FileExtensionFilter("Zip file", "zip"));

		var tabbedPanePaddingBorder = BorderFactory.createEmptyBorder(5, 5, 5, 5);

		var wrappedGeneralPanel = wrapWithAlign(generalPanel, PAGE_START, HORIZONTAL);
		var wrappedProtectionPanel = wrapWithAlign(protectionPanel, FIRST_LINE_START, NONE);

		wrappedGeneralPanel.setBorder(tabbedPanePaddingBorder);
		lumpsPanel.setBorder(createCompoundBorder(tabbedPanePaddingBorder, lumpsPanel.getBorder()));
		gameLumpsPanel.setBorder(createCompoundBorder(tabbedPanePaddingBorder, gameLumpsPanel.getBorder()));
		entitiesPanel.setBorder(createCompoundBorder(tabbedPanePaddingBorder, entitiesPanel.getBorder()));
		dependenciesPanel.setBorder(createCompoundBorder(tabbedPanePaddingBorder, dependenciesPanel.getBorder()));
		embeddedPanel.setBorder(createCompoundBorder(tabbedPanePaddingBorder, embeddedPanel.getBorder()));
		wrappedProtectionPanel.setBorder(tabbedPanePaddingBorder);

		tabbedPane.setTabInsets(new Insets(8, 8, 8, 8));
		tabbedPane.addTab("General", wrappedGeneralPanel);
		tabbedPane.addTab("Lumps", lumpsPanel);
		tabbedPane.addTab("Game lumps", gameLumpsPanel);
		tabbedPane.addTab("Entities", entitiesPanel);
		tabbedPane.addTab("Dependencies", dependenciesPanel);
		tabbedPane.addTab("Embedded files", embeddedPanel);
		tabbedPane.addTab("Protection", wrappedProtectionPanel);

		var contentPane = new JPanel(new BorderLayout());
		contentPane.add(tabbedPane, BorderLayout.CENTER);
		contentPane.add(createStatusBar(), BorderLayout.PAGE_END);

		setContentPane(contentPane);
		initMenuBar();
		initTransferHandler();

		onChanges();

		setTitle(NAME + " " + VERSION);

		URL iconUrl = requireNonNull(getClass().getResource("resources/icon.png"));
		Image icon = Toolkit.getDefaultToolkit().createImage(iconUrl);
		setIconImage(icon);

		pack();
		setMinimumSize(getSize());
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
	}

	private void initErrorDialog() {
		var dialogAppender = DialogAppender.createAppender("DialogAppender" + hashCode(), null, null, false, this);
		var appenderCloseable = Log4jUtil.addAppenders(dialogAppender);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				appenderCloseable.close();
			}
		});
	}

	private JComponent createStatusBar() {
		// none of the background tasks can report any meaningful progress, so all we show
		// is an indeterminate progress bar while one of them is running
		prgBusy.setIndeterminate(true);
		prgBusy.setPreferredSize(new Dimension(120, 12));
		prgBusy.setVisible(false);

		var statusBar = new JPanel(new BorderLayout(8, 0));
		statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
		statusBar.add(lblStatus, BorderLayout.CENTER);
		statusBar.add(prgBusy, BorderLayout.LINE_END);
		return statusBar;
	}

	private void initMenuBar() {
		menuItemOpenFile.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
		menuItemOpenFile.addActionListener(e -> {
			int result = fileChooser.showOpenDialog(this);
			if (result != JFileChooser.APPROVE_OPTION)
				return;

			lumpDstChooser.setCurrentDirectory(fileChooser.getCurrentDirectory());

			load(fileChooser.getSelectedFile().toPath());
		});

		var menuFile = new JMenu("File");
		menuFile.add(menuItemOpenFile);

		var menuBar = new JMenuBar();
		menuBar.add(menuFile);

		setJMenuBar(menuBar);
	}

	private void initTransferHandler() {
		setTransferHandler(new TransferHandler() {
			@Override
			public boolean canImport(TransferSupport support) {
				// don't accept another file while we're still busy with the last one
				return !busy && Arrays.stream(support.getDataFlavors())
						.anyMatch(DataFlavor::isFlavorJavaFileListType);
			}

			@Override
			public boolean importData(TransferSupport support) {
				if (!canImport(support))
					return false;

				List<File> files;
				try {
					files = getDroppedFiles(support.getTransferable());
				} catch (UnsupportedFlavorException | IOException e) {
					L.warn("Error in drag and drop", e);
					return false;
				}

				if (files.isEmpty())
					return false;

				load(files.get(files.size() - 1).toPath());
				return true;
			}
		});
	}

	/**
	 * Returns all files of a drag and drop transfer.
	 * <p>
	 * {@link DataFlavor#javaFileListFlavor} is only specified to transfer a {@link List},
	 * not a {@code List<File>}, so every element is checked individually instead of doing
	 * an unchecked cast of the whole list.
	 */
	private static List<File> getDroppedFiles(Transferable transferable)
			throws UnsupportedFlavorException, IOException {
		var data = (List<?>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
		return data.stream()
				.filter(File.class::isInstance)
				.map(File.class::cast)
				.toList();
	}

	private void onChanges() {
		generalPanel.update(model);
		lumpsPanel.update(model);
		gameLumpsPanel.update(model);
		entitiesPanel.update(model);
		dependenciesPanel.update(model);
		embeddedPanel.update(model);
		protectionPanel.update(model);
	}

	/**
	 * Loads the specified bsp file in the background, keeping the window responsive.
	 */
	private void load(Path bspFile) {
		if (busy)
			return;

		setBusy("Loading %s...".formatted(bspFile.getFileName()));

		model.load(bspFile, failureCause -> {
			setIdle();

			if (failureCause != null)
				showError("Error occurred loading file", failureCause);
		});
	}

	private void extractLumps(Set<Integer> lumpIndices) {
		Path lumpDst = chooseDstDialog(lumpDstChooser);
		if (lumpDst == null)
			return;

		extract("lump(s)", () -> model.extractLumps(lumpIndices, lumpDst));
	}

	private void extractGameLumps(Set<Integer> lumpIndices) {
		Path lumpDst = chooseDstDialog(lumpDstChooser);
		if (lumpDst == null)
			return;

		extract("game lump(s)", () -> model.extractGameLumps(lumpIndices, lumpDst));
	}

	private void extractFiles(Set<Integer> fileIndices) {
		Path filesDst = chooseDstDialog(embeddedFileDstChooser);
		if (filesDst == null)
			return;

		extract("embedded file(s)", () -> model.extractEmbeddedFiles(fileIndices, filesDst));
	}

	private void extractFilesRaw() {
		Path filesDst = chooseDstDialog(embeddedRawDstChooser);
		if (filesDst == null)
			return;

		extract("embedded files", () -> model.extractEmbeddedFilesRaw(filesDst));
	}

	/**
	 * Runs an extraction task in the background, keeping the window responsive, and reports
	 * its outcome to the user.
	 *
	 * @param what what is being extracted, used in the status and result messages
	 * @param task the blocking extraction, executed on a background thread
	 */
	private void extract(String what, ExtractionTask task) {
		if (busy)
			return;

		setBusy("Extracting %s...".formatted(what));

		new SwingWorker<Void, Void>() {
			@Override
			protected Void doInBackground() throws IOException {
				task.run();
				return null;
			}

			@Override
			protected void done() {
				setIdle();

				Throwable failureCause = null;
				try {
					get();
				} catch (InterruptedException e) {
					failureCause = e;
				} catch (ExecutionException e) {
					failureCause = e.getCause();
				}

				if (failureCause == null)
					JOptionPane.showMessageDialog(
							BspInfoFrame.this,
							"Successfully extracted %s.".formatted(what)
					);
				else
					showError("Error occurred extracting %s".formatted(what), failureCause);
			}
		}.execute();
	}

	/**
	 * Blocks every action that would start another background task and shows that we're
	 * working on something.
	 */
	private void setBusy(String status) {
		busy = true;
		lblStatus.setText(status);
		prgBusy.setVisible(true);
		updateActionsEnabled();
	}

	private void setIdle() {
		busy = false;
		lblStatus.setText(STATUS_IDLE);
		prgBusy.setVisible(false);
		updateActionsEnabled();
	}

	private void updateActionsEnabled() {
		menuItemOpenFile.setEnabled(!busy);
		lumpsPanel.setBusy(busy);
		gameLumpsPanel.setBusy(busy);
		embeddedPanel.setBusy(busy);
	}

	/**
	 * Reports a failed background task to the user.
	 * <p>
	 * Logged below {@code ERROR} on purpose: the {@link DialogAppender} installed by
	 * {@link #initErrorDialog()} turns every logged error into a dialog of its own, which
	 * would stack a second, redundant one on top of this.
	 */
	private void showError(String message, Throwable cause) {
		L.warn(message, cause);

		var detail = cause.getLocalizedMessage() != null ? cause.getLocalizedMessage() : cause.toString();
		JOptionPane.showMessageDialog(
				this,
				message + ":\n" + detail,
				"Error",
				JOptionPane.ERROR_MESSAGE
		);
	}

	private Path chooseDstDialog(JFileChooser fileChooser) {
		int result = fileChooser.showSaveDialog(this);
		if (result == JFileChooser.APPROVE_OPTION)
			return fileChooser.getSelectedFile().toPath();
		else
			return null;
	}

	/**
	 * A blocking extraction, run on a background thread by {@link #extract}.
	 */
	@FunctionalInterface
	private interface ExtractionTask {
		void run() throws IOException;
	}
}
