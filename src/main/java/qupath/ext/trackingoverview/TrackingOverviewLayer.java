package qupath.ext.trackingoverview;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.viewer.OverviewLayer;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.ResourceBundle;

/**
 * An {@link OverviewLayer} that records which parts of the image have been looked at, and at what magnification,
 * and paints the outline of each explored region on top of the overview thumbnail.
 * <p>
 * The record is a single RGBA image the size of the overview. The RGB part is a base colour
 * (black for brightfield, white for fluorescence) and the alpha part is a 4-level mask,
 * one level per magnification range. Each time the visible region changes, the pixels inside it are
 * lowered to the level of the current magnification, so a pixel always holds the highest magnification
 * at which it has been seen. Region outlines are found by looking for neighbours with a higher value.
 * <p>
 * Tracking starts when the layer is created (i.e. when the user chooses the tracking custom overview),
 * and is lost when another overview is chosen, the image changes, or the map is reset.
 * Everything here runs on the JavaFX Application Thread.
 */
class TrackingOverviewLayer implements OverviewLayer {

	private static final Logger logger = LoggerFactory.getLogger(TrackingOverviewLayer.class);
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.trackingoverview.strings");

	private static final int BACKGROUND_MASK_ARGB = 0x80000000;

	/**
	 * Magnification range: the mask value stored for it, and the colour of its outline.
	 */
	private record MagnificationLayer(double minMag, int argbMask, int argbColor) {}

	private MagnificationLayer[] magLayers;
	private int backgroundColorARGB = BACKGROUND_MASK_ARGB;

	// The image the tracking map belongs to - a new image means a new map
	private ImageData<BufferedImage> trackedImageData = null;

	private WritableImage trackingOverlay = null;
	private WritableImage contouredOverlay = null;

	private boolean trackVisible = true;

	// Last context seen - needed to request a repaint from the context menu
	private Context lastContext;

	private final CheckMenuItem showTrackMapItem = new CheckMenuItem(resources.getString("menu.show-track"));
	private final MenuItem resetTrackMapItem = new MenuItem(resources.getString("menu.reset-track"));

	TrackingOverviewLayer() {
		showTrackMapItem.setSelected(trackVisible);
		showTrackMapItem.setOnAction(e -> {
			trackVisible = showTrackMapItem.isSelected();
			requestRepaint();
		});
		resetTrackMapItem.setOnAction(e -> {
			// The map is recreated (and the current view recorded) by the next paint
			trackingOverlay = null;
			contouredOverlay = null;
			logger.info("Tracking reset");
			requestRepaint();
		});
	}

	private void requestRepaint() {
		var context = lastContext;
		if (context != null)
			context.requestRepaint();
	}

	@Override
	public List<? extends MenuItem> getContextMenuItems(Context context) {
		lastContext = context;
		showTrackMapItem.setSelected(trackVisible);
		return List.of(showTrackMapItem, resetTrackMapItem);
	}

	@Override
	public void paint(GraphicsContext g, Context context) {
		lastContext = context;
		QuPathViewer viewer = context.getViewer();
		if (viewer == null || !viewer.hasServer() || viewer.getImageData() == null)
			return;

		int width = (int) context.getWidth();
		int height = (int) context.getHeight();
		if (width <= 0 || height <= 0)
			return;

		syncOverlaySize(viewer.getImageData(), width, height);
		updateTrackingOverlay(viewer, context);

		if (trackVisible && contouredOverlay != null)
			g.drawImage(contouredOverlay, 0, 0);
	}

	/**
	 * Make sure there is a tracking map that matches the current image and overview size.
	 */
	private void syncOverlaySize(ImageData<BufferedImage> imageData, int width, int height) {
		// New image, or no map yet: start from scratch
		if (imageData != trackedImageData || trackingOverlay == null) {
			trackedImageData = imageData;
			initializeTrackingOverlay(imageData, width, height);
			return;
		}
		int oldWidth = (int) trackingOverlay.getWidth();
		int oldHeight = (int) trackingOverlay.getHeight();
		if (oldWidth == width && oldHeight == height)
			return;

		// The overview was resized (same image, so same aspect ratio) -> rescale the existing map.
		// A different aspect ratio means the thumbnail is not the one for this image (yet), so start again.
		double oldAspect = (double) oldWidth / oldHeight;
		double newAspect = (double) width / height;
		if (Math.abs(oldAspect - newAspect) > 0.02 * newAspect) {
			initializeTrackingOverlay(imageData, width, height);
		} else {
			scaleTrackingOverlayNearestNeighbor(width, height);
			recomputeContoursOverWholeTrackingMap();
		}
	}

	/**
	 * Initialize the tracking overlay with all bits set
	 */
	private void initializeTrackingOverlay(ImageData<BufferedImage> imageData, int width, int height) {
		trackingOverlay = new WritableImage(width, height);
		contouredOverlay = new WritableImage(width, height);

		PixelWriter writer = trackingOverlay.getPixelWriter();

		// initial base RGB color (black for brightfield and white for fluorescence)
		int baseRGB = imageData.isBrightfield() ? 0x00000000 : 0x00FFFFFF;
		backgroundColorARGB = BACKGROUND_MASK_ARGB | baseRGB;

		// Define the layers and include the initial color
		magLayers = new MagnificationLayer[]{
				new MagnificationLayer(12.0, 0x00000000, 0xFF71FF9E), // If alpha is 0, the pixel is stored as RGB=000 as well.
				new MagnificationLayer(5.12, 0x20000000 | baseRGB, 0xFFFFFF88),
				new MagnificationLayer(2.8, 0x40000000 | baseRGB, 0xFFFF7171),
				new MagnificationLayer(1.38, 0x60000000 | baseRGB, 0xFFFFC071),
		};

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				writer.setArgb(x, y, backgroundColorARGB);
			}
		}
		contouredOverlay.getPixelWriter().setPixels(0, 0, width, height, trackingOverlay.getPixelReader(), 0, 0);

		logger.debug("Initialized tracking overlay: {}x{}, base RGB: 0x{}",
				width, height, Integer.toHexString(baseRGB));
	}

	/**
	 * Scale tracking overlay using nearest neighbor interpolation to preserve exact ARGB values.
	 * This is critical for maintaining coherent pixel values for contour computation.
	 */
	private void scaleTrackingOverlayNearestNeighbor(int newWidth, int newHeight) {
		int srcWidth = (int) trackingOverlay.getWidth();
		int srcHeight = (int) trackingOverlay.getHeight();

		logger.debug("Scaling tracking overlay from {}x{} to {}x{} using nearest neighbor",
				srcWidth, srcHeight, newWidth, newHeight);

		WritableImage scaledTracking = new WritableImage(newWidth, newHeight);
		PixelReader reader = trackingOverlay.getPixelReader();
		PixelWriter writer = scaledTracking.getPixelWriter();

		double xRatio = (double) srcWidth / newWidth;
		double yRatio = (double) srcHeight / newHeight;

		for (int y = 0; y < newHeight; y++) {
			for (int x = 0; x < newWidth; x++) {
				int srcX = Math.min((int) (x * xRatio), srcWidth - 1);
				int srcY = Math.min((int) (y * yRatio), srcHeight - 1);
				// Copy pixel directly (preserves exact ARGB)
				writer.setArgb(x, y, reader.getArgb(srcX, srcY));
			}
		}

		trackingOverlay = scaledTracking;
		contouredOverlay = new WritableImage(newWidth, newHeight);
	}

	/**
	 * Recompute contours over the ENTIRE tracking map.
	 * This ensures contours are accurate for the new resolution after scaling.
	 */
	private void recomputeContoursOverWholeTrackingMap() {
		int width = (int) trackingOverlay.getWidth();
		int height = (int) trackingOverlay.getHeight();

		int[] maskPixels = new int[width * height];
		trackingOverlay.getPixelReader().getPixels(0, 0, width, height,
				PixelFormat.getIntArgbInstance(), maskPixels, 0, width);

		int[] outputPixels = maskPixels.clone();
		applyMorphologicalEdge(maskPixels, outputPixels, width, height);

		contouredOverlay.getPixelWriter().setPixels(0, 0, width, height,
				PixelFormat.getIntArgbInstance(), outputPixels, 0, width);
	}

	/*
	 * Detect the edge of a bitplane in the input array and apply a contour on the output pixel array
	 */
	private void applyMorphologicalEdge(int[] maskPixels, int[] outputPixels, int w, int h) {
		for (int y = 1; y < h - 1; y++) {
			for (int x = 1; x < w - 1; x++) {
				int idx = y * w + x;
				for (MagnificationLayer magLayer : magLayers) {
					int argbMask = magLayer.argbMask();
					if (maskPixels[idx] == argbMask) {
						boolean isEdge =
								Integer.compareUnsigned(maskPixels[idx - w], argbMask) > 0 ||
								Integer.compareUnsigned(maskPixels[idx + w], argbMask) > 0 ||
								Integer.compareUnsigned(maskPixels[idx - 1], argbMask) > 0 ||
								Integer.compareUnsigned(maskPixels[idx + 1], argbMask) > 0 ||
								Integer.compareUnsigned(maskPixels[idx - w - 1], argbMask) > 0 ||
								Integer.compareUnsigned(maskPixels[idx - w + 1], argbMask) > 0 ||
								Integer.compareUnsigned(maskPixels[idx + w - 1], argbMask) > 0 ||
								Integer.compareUnsigned(maskPixels[idx + w + 1], argbMask) > 0;
						if (isEdge) {
							outputPixels[idx] = magLayer.argbColor();
							break;
						}
					}
				}
			}
		}
	}

	/**
	 * Update tracking overlay based on current visible region and magnification
	 */
	private void updateTrackingOverlay(QuPathViewer viewer, Context context) {
		var shapeVisible = context.getVisibleShape();
		if (trackingOverlay == null || shapeVisible == null)
			return;

		// Get bounding box of visible region
		Rectangle2D bounds = shapeVisible.getBounds2D();
		int minX = Math.max(0, (int) Math.floor(bounds.getMinX()));
		int minY = Math.max(0, (int) Math.floor(bounds.getMinY()));
		int maxX = Math.min((int) trackingOverlay.getWidth() - 1, (int) Math.ceil(bounds.getMaxX()));
		int maxY = Math.min((int) trackingOverlay.getHeight() - 1, (int) Math.ceil(bounds.getMaxY()));

		if (minX >= maxX || minY >= maxY)
			return;

		double currentMag = viewer.getMagnification();

		// Determine which alpha mask to use based on current magnification
		int currentARGB = backgroundColorARGB;
		for (MagnificationLayer magLayer : magLayers) {
			if (currentMag >= magLayer.minMag()) {
				currentARGB = magLayer.argbMask();
				break;
			}
		}

		PixelReader reader = trackingOverlay.getPixelReader();
		PixelWriter writer = trackingOverlay.getPixelWriter();

		// Nibble pixels in the visible region
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				if (shapeVisible.contains(x, y)) {
					int argb = reader.getArgb(x, y);
					if (Integer.compareUnsigned(argb, currentARGB) > 0)
						writer.setArgb(x, y, currentARGB);
				}
			}
		}

		// Expand crop by 1 pixel for edge detection, clamped to image bounds
		int padMinX = Math.max(0, minX - 1);
		int padMinY = Math.max(0, minY - 1);
		int padMaxX = Math.min((int) trackingOverlay.getWidth() - 1, maxX + 1);
		int padMaxY = Math.min((int) trackingOverlay.getHeight() - 1, maxY + 1);

		int padWidth = padMaxX + 1 - padMinX;
		int padHeight = padMaxY + 1 - padMinY;

		int[] maskPixels = new int[padWidth * padHeight];
		trackingOverlay.getPixelReader().getPixels(padMinX, padMinY, padWidth, padHeight,
				PixelFormat.getIntArgbInstance(), maskPixels, 0, padWidth);

		int[] outputPixels = maskPixels.clone();
		applyMorphologicalEdge(maskPixels, outputPixels, padWidth, padHeight);

		// Write back only the original (unpadded) crop region
		int innerOffsetX = minX - padMinX;  // 0 or 1
		int innerOffsetY = minY - padMinY;  // 0 or 1
		int cropWidth = maxX + 1 - minX;
		int cropHeight = maxY + 1 - minY;
		contouredOverlay.getPixelWriter().setPixels(minX, minY, cropWidth, cropHeight,
				PixelFormat.getIntArgbInstance(), outputPixels, innerOffsetY * padWidth + innerOffsetX, padWidth);
	}
}
