package qupath.ext.trackingoverview;

import qupath.lib.gui.viewer.OverviewCustomization;
import qupath.lib.gui.viewer.OverviewLayer;
import qupath.lib.gui.viewer.QuPathViewer;

import java.util.ResourceBundle;

/**
 * The entry this extension adds to the overview's <i>Custom overview</i> submenu.
 * Selecting it for a viewer starts tracking which parts of the image that viewer has shown,
 * and draws the explored regions on its overview.
 */
class TrackingOverviewCustomization implements OverviewCustomization {

	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.trackingoverview.strings");

	@Override
	public String getName() {
		return resources.getString("customization.name");
	}

	@Override
	public OverviewLayer createLayer(QuPathViewer viewer) {
		return new TrackingOverviewLayer();
	}
}
