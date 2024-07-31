package lcms4j.xyz.imaging;

/**
 * Image type identifiers.
 */
public enum ImageType {
	UNKNOWN("Unknown"),
	PNG("PNG"),
	JPEG("JPEG"),
	BMP("BMP"),
	TIFF_LE("TIFF_LE"),
	TIFF_BE("TIFF_BE");
	
	private final String m_description;
	
	private ImageType(String description) {
		m_description = description;
	}
	
	@Override
	public String toString() {
		return m_description;
	}
}
