package qupath.ext.trackingoverview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.viewer.OverviewCustomizations;

import java.util.ResourceBundle;

/**
 * Adds a "tracking" custom overview: it records which regions of an image have been viewed, and at which
 * magnification, and draws their outlines on the viewer overview.
 * <p>
 * This relies on the overview layer and customization hooks ({@code qupath.lib.gui.viewer.OverviewLayer},
 * {@code OverviewCustomization} and {@code OverviewCustomizations}), which are not (yet) part of stock QuPath.
 * If they are missing, the extension logs a warning and does nothing.
 * <p>
 * The tracking overview is offered in the overview's <i>Custom overview</i> context submenu, one viewer at a time.
 * <p>
 * <b>Important!</b> The name &amp; package of this class must be consistent with
 * <pre>
 *     /resources/META-INF/services/qupath.lib.gui.extensions.QuPathExtension
 * </pre>
 */
public class TrackingOverviewExtension implements QuPathExtension {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.trackingoverview.strings");
	private static final Logger logger = LoggerFactory.getLogger(TrackingOverviewExtension.class);

	private static final String EXTENSION_NAME = resources.getString("name");
	private static final String EXTENSION_DESCRIPTION = resources.getString("description");
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.8.0");

	private boolean isInstalled = false;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;

		// Check that the running QuPath has the overview hooks, before loading any class that needs them
		if (!hasOverviewHookSupport(qupath)) {
			logger.warn("{}: this QuPath build has no overview customization support - the extension will do nothing", getName());
			Dialogs.showErrorMessage(resources.getString("error"), resources.getString("error.unsupported-qupath"));
			return;
		}
		install();
	}

	private static boolean hasOverviewHookSupport(QuPathGUI qupath) {
		try {
			Class.forName("qupath.lib.gui.viewer.OverviewCustomizations", false, qupath.getClass().getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	private static void install() {
		OverviewCustomizations.register(new TrackingOverviewCustomization());
	}

	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}

	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}
}
