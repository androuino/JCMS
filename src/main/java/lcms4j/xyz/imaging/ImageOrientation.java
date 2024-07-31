package lcms4j.xyz.imaging;

/**
 * Pixel orientation values for a digital image.
 */
public enum ImageOrientation {
	TOP("TOP"),
	RIGHT("RIGHT"),
	LEFT("LEFT"),
	DOWN("DOWN");
	
	private final String m_description;
	
	private ImageOrientation(String description) {
		m_description = description;
	}
	
	@Override
	public String toString() {
		return m_description;
	}
}
