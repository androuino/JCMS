package lcms4j.xyz.controls;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import lcms4j.xyz.LCMS4J;
import lcms4j.xyz.imaging.*;

/**
 * An <code>IccTransformer</code> performs ICC color transformation on images.<br><br>
 * This class uses native LittleCMS C library for managing ICC color transformations, and may allocate resources that
 * will not be automatically freed by JVM's garbage collection. It is mandatory to call {@link #dispose() dispose()} method
 * on any <code>IccTransformer</code> object when it is no longer needed in order to free all native resources and avoid memory leaks.
 * 
 * @author enric
 *
 */
public class IccTransformer {
	/** Destination profile for color transformations */
	private IccProfile m_destinationProfile = null;
	/** Default RGB profile to be used as source for color transformations */
	private IccProfile m_defaultRGB = null;
	/** Default CMYK profile to be used as source for color transformations */
	private IccProfile m_defaultCMYK = null;
	/** Default Gray profile to be used as source for color transformations */
	private IccProfile m_defaultGray = null; 
	/** Intent for color transformations */
	private int m_intent = LCMS4J.INTENT_RELATIVE_COLORIMETRIC;
	/** Flags for color transformations */
	private int m_flags = LCMS4J.CMSFLAGS_BLACKPOINTCOMPENSATION;
	/** Use embedded profiles in source files. If false, default profiles are used as source profiles */ 
	private boolean m_useEmbeddedProfiles = true;
	/** Quality of JPEG compression for output files (0 to 1)*/
	private float m_jpegQuality = 1f;
	
	/**
	 * Creates an <code>IccTransformer</code> object that will perform color transformations to a
	 * predefined destination profile.
	 * 
	 * @param destinationProfile Destination ICC profile for color transformations
	 * @param intent Intent for color transformations (<code>LCMS4J.INTENT_*</code>)
	 * @param blackPointCompensation If <code>true</code>, black point compensation is used for colorimetric relative transformations
	 * @throws LCMS4JException If destination profile contains invalid data
	 */
	public IccTransformer(ICC_Profile destinationProfile, int intent, boolean blackPointCompensation) throws LCMS4JException {
		m_destinationProfile = new IccProfile(destinationProfile.getData());
		m_intent = intent;
		m_flags = (blackPointCompensation ? LCMS4J.CMSFLAGS_BLACKPOINTCOMPENSATION : 0);
		m_defaultGray = new IccProfile(IccProfile.PROFILE_GRAY);
		m_defaultRGB = new IccProfile(IccProfile.PROFILE_SRGB);
		m_defaultCMYK = new IccProfile(IccProfile.PROFILE_FOGRA39);
	}
	
	/**
	 * Performs color transformation on a <code>BufferedImage</code>.<br>
	 * Destination profile, intent and flags should have been set previously.
	 * 
	 * @param image Original image to be transformed
	 * @param srcProfile Source ICC profile of the image
	 * @return Transformed image
	 * @throws LCMS4JException if any error occurs during color transformation
	 */
	public BufferedImage transform(BufferedImage image, ICC_Profile srcProfile) throws LCMS4JException {
		IccProfile src = null;
		BufferedImage result;
		try {
			src = new IccProfile(srcProfile.getData());
			result = transform(image, src, m_destinationProfile, m_intent, m_flags);
		} finally {
			if (src != null) src.dispose();
		}
		return result;
	}
	
	/**
	 * Performs color transformation on an image file. Transformed image is returned as a new <code>BufferedImage</code>.<br>
	 * Destination profile, intent and flags should have been set previously
	 * 
	 * @param srcImage Source image file
	 * @return the color transformed image
	 * @throws LCMS4JException if any error occurs during color transformation
	 */
	@SuppressWarnings("unused")
	public BufferedImage transform(File srcImage) throws LCMS4JException {
		// Validate input image
		if (srcImage == null) {
			throw new LCMS4JException("Source image can not be null.");
		}
		// Read input image data
		Raster raster = null;
		int width = 0;
		int height = 0;
		int numBands = 0;
		byte[] rasterData = null;
		ImageReader reader = null;
		try (ImageInputStream input = ImageIO.createImageInputStream(srcImage)) {
			Iterator<ImageReader> it = ImageIO.getImageReaders(input);
			
			while (it.hasNext() && (raster == null)) {
				reader = it.next();
				reader.setInput(input);
				if (!reader.canReadRaster()) {
					reader.dispose();
					continue;
				}
				raster = reader.readRaster(0, null);
				rasterData = ((DataBufferByte)raster.getDataBuffer()).getData();
				numBands = raster.getNumBands();
				width = reader.getWidth(0);
				height = reader.getHeight(0);
				reader.dispose();
			}
		} catch (IOException e) {
			try {
                assert reader != null;
                reader.dispose();} catch (Exception ex) {/*Ignore*/}
			throw new LCMS4JException("Unable to read source image raster: "+e.getMessage());
		}
		if (raster == null) {
			try {
				BufferedImage bi = ImageIO.read(srcImage);
				raster = bi.getRaster();
				rasterData = ((DataBufferByte)raster.getDataBuffer()).getData();
				numBands = raster.getNumBands();
				width = bi.getWidth();
				height = bi.getHeight();				
			} catch (IOException e) {
				throw new LCMS4JException("Unable to read source image: "+e.getMessage());
			}			
		}
        // Read image metadata
		ImageMetadata md = ImageMetadata.getInstance(srcImage);
		JPEGMetadata jpmd = null;
		if (md.getImageType() == ImageType.JPEG) {
			jpmd = (JPEGMetadata) md;
		}
		if (md.isIndexed()) {
			throw new LCMS4JException("Unsupported input image color model (indexed).");
		}
		// Determine raster format and default profile
		int inputFormat = -1;
		IccProfile inputProfile = null;
		switch (numBands) {
			// Grayscale
			case 1:
				inputProfile = m_defaultGray;
				inputFormat = LCMS4J.TYPE_GRAY_8;
				break;
			// RGB
			case 3:
				inputProfile = m_defaultRGB;
				inputFormat = LCMS4J.TYPE_RGB_8;
				if (md.getImageType() == ImageType.JPEG) {
                    assert jpmd != null;
                    if (!jpmd.isAdobeApp14Found() || (jpmd.getAdobeColorTransform() == JPEGMetadata.ADOBE_TRANSFORM_YCbCr)) {
						convertYCbCrToRGB(rasterData);
					}
				} else if (md.getImageType() == ImageType.PNG) {
					inputFormat = LCMS4J.TYPE_BGR_8;
				}
				break;
			// CMYK
			case 4:
				inputProfile = m_defaultCMYK;
				if (md.getImageType() == ImageType.JPEG) {
                    assert jpmd != null;
                    if (jpmd.isAdobeApp14Found()) {
						switch (jpmd.getAdobeColorTransform()) {
							case JPEGMetadata.ADOBE_TRANSFORM_UNKNOWN:
								inputFormat = LCMS4J.TYPE_CMYK_8_REV;
								break;
							case JPEGMetadata.ADOBE_TRANSFORM_YCCK:
								convertYcckToCmyk(rasterData, true);
								inputFormat = LCMS4J.TYPE_CMYK_8;
								break;
						}
					}
				} else if (md.getImageType() == ImageType.PNG) {
					inputFormat = LCMS4J.TYPE_ABGR_8;
				} else {
					inputFormat = LCMS4J.TYPE_CMYK_8_REV;
				}
				break;
		}
		if (inputFormat == -1) {
			throw new LCMS4JException("Unsupported input image raster type.");
		}
		// Generate output BufferedImage
		int outputType = getBufferedImageType(m_destinationProfile, md.isTransparent());
		if (outputType == -1) {
			throw new LCMS4JException("Unsupported output profile type.");
		}
		BufferedImage output = new BufferedImage(width, height, outputType);
		byte[] outputData = ((DataBufferByte)output.getRaster().getDataBuffer()).getData();
		int outputNumBands = output.getRaster().getNumBands();
		int outputFormat = getLcms4jBufferType(output);
		if (outputFormat == 0) {
			throw new LCMS4JException("Unsupported output image type");
		}
		// Perform transformation
		boolean usingEmbeddedProfile = false;
		IccTransform icctransform = null;
		try {
			// Determine input profile
			if (m_useEmbeddedProfiles) {
				ICC_Profile profile = md.getIccProfile();
				if (profile != null) {
					inputProfile = new IccProfile(profile.getData());
					usingEmbeddedProfile = true;
				} else if (md.getImageType() == ImageType.JPEG) {
                    assert jpmd != null;
                    long exifCS = jpmd.getExifColorSpace();
					if ((exifCS == JPEGMetadata.EXIF_CS_SRGB) && (numBands == 3)) {
						inputProfile = new IccProfile(IccProfile.PROFILE_SRGB);
						usingEmbeddedProfile = true;
					} else if ((exifCS == JPEGMetadata.EXIF_CS_ADOBERGB) && (numBands == 3)) {
						inputProfile = new IccProfile(IccProfile.PROFILE_ADOBERGB);
						usingEmbeddedProfile = true;
					}
				}
			}
			// Perform transformation
			icctransform = new IccTransform(inputProfile, inputFormat, m_destinationProfile, outputFormat, m_intent, m_flags);
			byte[] inputBuffer = new byte[width * numBands];
			byte[] outputBuffer = new byte[width * outputNumBands];
			for (int line=0; line<height; line++) {
				System.arraycopy(rasterData, line * width * numBands, inputBuffer, 0, width * numBands);
				icctransform.transform(inputBuffer, outputBuffer, width);
				System.arraycopy(outputBuffer, 0, outputData, line * width * outputNumBands, width * outputNumBands);
			}
		} finally {
			if (icctransform != null) icctransform.dispose();
			if (usingEmbeddedProfile) inputProfile.dispose();
		}
		// Recover transparency
		if (md.isTransparent()) {
			for (int y=0; y<height; y++) {
				for (int x=0; x<width; x++) {
					outputData[(y*width + x) * outputNumBands] = rasterData[(y*width + x) * numBands];
				}
			}
		}
		return output;
	}
	
	/**
	 * Performs color transformaton on an image file. Transformed image is saved to another (or the same) file.<br>
	 * Destination profile, intent and flags should have been set previously
	 * 
	 * @param srcImage Source image file
	 * @param dstImage Destination image file
	 * @throws LCMS4JException if any error occurs during color transformation
	 */
	public void transform(File srcImage, File dstImage) throws LCMS4JException {
		// Transform source image
		BufferedImage output = transform(srcImage);

		// Determine output image type by extension
		ImageType type = ImageType.JPEG;
		try {
			String ext = dstImage.getName().substring(dstImage.getName().lastIndexOf(".")+1);
			if (ext.matches("(?i)png")) {
				type = ImageType.PNG;
			}
		} catch (Exception e) {
			/* Ignore */
		}

		// Determine input DPI
		ImageMetadata md = ImageMetadata.getInstance(srcImage);
		double dpi = md.getDpiX();

		// Save output image to file
		switch (type) {
			case JPEG:
				if (!ImageWriter.writeJpeg(output, dstImage, m_jpegQuality, (int)Math.round(dpi), m_destinationProfile.getICC_Profile())) {
					throw new LCMS4JException("Unable to write output image");
				}
				break;

			case PNG:
				if (!ImageWriter.writePng(output, dstImage, dpi, m_destinationProfile.getICC_Profile())) {
					throw new LCMS4JException("Unable to write output image");
				}
				break;

			default:
				throw new LCMS4JException("Unsupported output image type: " + type);
		}
	}
	
	/**
	 * Sets the default RGB profile that will be used as source profile for color transformations.
	 * 
	 * @param profile Default RGB profile for color transformations
	 * @throws LCMS4JException If provided ICC profile is not a valid RGB profile.
	 * @throws IllegalArgumentException If <code>profile</code> is <code>null</code>.
	 */
	public void setDefaultRGB(ICC_Profile profile) throws LCMS4JException {
		if (profile == null) {
			throw new IllegalArgumentException("Profile can not be null.");
		}
		if (profile.getColorSpaceType() != ColorSpace.TYPE_RGB) {
			throw new LCMS4JException("Profile should be in RGB color space.");
		}
		m_defaultRGB.dispose();
		m_defaultRGB = new IccProfile(profile.getData());
	}

	/**
	 * Sets the default CMYK profile that will be used as source profile for color transformations.
	 * 
	 * @param profile Default CMYK profile for color transformations
	 * @throws LCMS4JException If provided ICC profile is not a valid CMYK profile.
	 * @throws IllegalArgumentException If <code>profile</code> is <code>null</code>.
	 */
	public void setDefaultCMYK(ICC_Profile profile) throws LCMS4JException {
		if (profile == null) {
			throw new IllegalArgumentException("Profile can not be null.");
		}
		if (profile.getColorSpaceType() != ColorSpace.TYPE_CMYK) {
			throw new LCMS4JException("Profile should be in CMYK color space.");
		}
		m_defaultCMYK.dispose();
		m_defaultCMYK = new IccProfile(profile.getData());
	}
	
	/**
	 * Sets the default Gray profile that will be used as source profile for color transformations.
	 * 
	 * @param profile Default Gray profile for color transformations
	 * @throws LCMS4JException If provided ICC profile is not a valid Gray profile.
	 * @throws IllegalArgumentException If <code>profile</code> is <code>null</code>.
	 */
	public void setDefaultGray(ICC_Profile profile) throws LCMS4JException {
		if (profile == null) {
			throw new IllegalArgumentException("Profile can not be null.");
		}
		if (profile.getColorSpaceType() != ColorSpace.TYPE_GRAY) {
			throw new LCMS4JException("Profile should be in Gray color space.");
		}
		m_defaultGray.dispose();
		m_defaultGray = new IccProfile(profile.getData());
	}
	
	/**
	 * Gets the default Gray profile that will be used as source profile for color transformations.
	 * 
	 * @return Default Gray profile for color transformations
	 */
	public ICC_Profile getDefaultGray() {
		return m_defaultGray.getICC_Profile();
	}
	
	/**
	 * Gets the default RGB profile that will be used as source profile for color transformations.
	 * 
	 * @return Default RGB profile for color transformations
	 */
	public ICC_Profile getDefaultRGB() {
		return m_defaultRGB.getICC_Profile();
	}

	/**
	 * Gets the default CMYK profile that will be used as source profile for color transformations.
	 * 
	 * @return Default CMYK profile for color transformations
	 */
	public ICC_Profile getDefaultCMYK() {
		return m_defaultCMYK.getICC_Profile();
	}

	/**
	 * Defines if embedded profiles in source files should be used as source profiles for color transformations.
	 * If it is set to <code>false</code>, default input profiles are used instead of embedded profiles.
	 * 
	 * @param useEmbeddedProfiles <code>true</code> to use embedded profiles, <code>false</code> to force use of default profiles
	 * @see #setDefaultRGB(ICC_Profile)
	 * @see #setDefaultCMYK(ICC_Profile)
	 * @see #setDefaultGray(ICC_Profile)
	 */
	public void setUseEmbeddedProfiles(boolean useEmbeddedProfiles) {
		m_useEmbeddedProfiles = useEmbeddedProfiles;
	}
	
	/**
	 * Frees any native resources allocated by this object.
	 */
	public void dispose() {
		if (m_destinationProfile != null) m_destinationProfile.dispose();
		if (m_defaultRGB != null) m_defaultRGB.dispose();
		if (m_defaultCMYK != null) m_defaultCMYK.dispose();
		if (m_defaultGray != null) m_defaultGray.dispose();
	}

	/**
	 * Perform ICC color transformation on an image.
	 * 
	 * @param image Original image to be color transformed.
	 * @param src Source profile for color transformation.
	 * @param dst Destination profile for color transformation.
	 * @param intent Transformation rendering intent (LCMS4J.INTENT_*)
	 * @param flags Flags that modify transformation algorithm (LCMS4J.CCFLAGS_*)
	 * @return Transformed image.
     */
	public static BufferedImage transform(BufferedImage image, IccProfile src, IccProfile dst, int intent, int flags) throws LCMS4JException {
		// Validate input parameters
		if (image == null) {
			throw new IllegalArgumentException("Image must not be null");
		}
		if (src == null) {
			throw new IllegalArgumentException("Source profile must not be null");
		}
		if (dst == null) {
			throw new IllegalArgumentException("Destination profile must not be null");
		}
		// Detect original image type and check if source profile is compatible
		int inputFormat = getLcms4jBufferType(image);
		if (inputFormat == 0) {
			throw new LCMS4JException("Unsupported input image type");
		}
		if (!isProfileValid(image, src)) {
			throw new LCMS4JException("Source profile not compatible with source image type");
		}
		// Check input data buffer type
		int inputDataBufferType = image.getRaster().getDataBuffer().getDataType();
		if (inputDataBufferType != DataBuffer.TYPE_BYTE) {
			throw new LCMS4JException("Unsupported input data buffer type");
		}
		// Define output image based on destination profile
		boolean transparency = false;
		transparency |= (image.getType() == BufferedImage.TYPE_4BYTE_ABGR);
		transparency |= (image.getType() == BufferedImage.TYPE_4BYTE_ABGR_PRE);
		int outputImageType = getBufferedImageType(dst, transparency);
		if (outputImageType == -1) {
			throw new LCMS4JException("Unsupported output profile type");
		}
		BufferedImage outputImage = new BufferedImage(image.getWidth(), image.getHeight(), outputImageType);
		int outputFormat = getLcms4jBufferType(outputImage);
		if (outputFormat == 0) {
			throw new LCMS4JException("Unsupported output image type");
		}
		// Perform transformation
		IccTransform icctransform = null;
		try {
			// Generate IccTransform object
			icctransform = new IccTransform(src, inputFormat, dst, outputFormat, intent, flags);
			// Prepare data buffers
			byte[] in = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
			byte[] out = ((DataBufferByte) outputImage.getRaster().getDataBuffer()).getData();
			int pixels = image.getWidth() * image.getHeight();
			// Perform transformation
			icctransform.transform(in, out, pixels);
			// Recover transparency data
			if (transparency) {
				for (int y=0; y<image.getHeight(); y++) {
					for (int x=0; x<image.getWidth(); x++) {
						out[(y*image.getWidth() + x) * 4] = in[(y*image.getWidth() + x) * 4];
					}
				}				
			}
		} finally {
			// Dispose all native resources
			if (icctransform != null) icctransform.dispose();	
		}
		return outputImage;
	}

	/**
	 * Perform ICC color transformation on an image.
	 * 
	 * @param image Original image to be color transformed.
	 * @param src Source profile for color transformation.
	 * @param dst Destination profile for color transformation.
	 * @param intent Transformation rendering intent (LCMS4J.INTENT_*)
	 * @param flags Flags that modify transformation algorithm (LCMS4J.CMSFLAGS_*)
	 * @return Transformed image.
     */
	public static BufferedImage transform(BufferedImage image, ICC_Profile src, ICC_Profile dst, int intent, int flags) throws LCMS4JException {
		IccProfile srcProfile = null;
		IccProfile dstProfile = null;
		BufferedImage output = null;

		try {
			srcProfile = new IccProfile(src.getData());
			dstProfile = new IccProfile(dst.getData());
			output = transform(image, srcProfile, dstProfile, intent, flags);
		} finally {
			if (srcProfile != null) srcProfile.dispose();
			if (dstProfile != null) dstProfile.dispose();
		}
		return output;
	}
	
	/**
	 * Gets LCMS4J buffer type that fits the raster data of an image
	 * @param image Image to analyze
	 * @return LCMS4J buffer type Id (<code>LCMS4J.TYPE_*</code>), or <code>0</code> if no valid type is found
	 */
	private static int getLcms4jBufferType(BufferedImage image) {
		int imageType = image.getType();
		int bufferType = 0;
		
		switch (imageType) {
			case BufferedImage.TYPE_3BYTE_BGR:
				bufferType = LCMS4J.TYPE_BGR_8;
				break;
			case BufferedImage.TYPE_4BYTE_ABGR:
			case BufferedImage.TYPE_4BYTE_ABGR_PRE:
				bufferType = LCMS4J.TYPE_ABGR_8;
				break;
			case BufferedImage.TYPE_BYTE_GRAY:
				bufferType = LCMS4J.TYPE_GRAY_8;
				break;
			case BufferedImage.TYPE_BYTE_BINARY:
			case BufferedImage.TYPE_BYTE_INDEXED:
			case BufferedImage.TYPE_CUSTOM:
			case BufferedImage.TYPE_INT_ARGB:
			case BufferedImage.TYPE_INT_ARGB_PRE:
			case BufferedImage.TYPE_INT_BGR:
			case BufferedImage.TYPE_INT_RGB:
			case BufferedImage.TYPE_USHORT_555_RGB:
			case BufferedImage.TYPE_USHORT_565_RGB:
			case BufferedImage.TYPE_USHORT_GRAY:
                break;
		}
		return bufferType;
	}

	/**
	 * Gets <code>BufferedImage</code> type that fits the components of an ICC profile.
	 * @param profile Reference ICC profile
	 * @return <code>BufferedImage</code> type (<code>BufferedImage.TYPE_*</code>), or <code>-1</code> if no valid type is found
	 */
	private static int getBufferedImageType(IccProfile profile, boolean transparency) {
		int bufferedImageType = -1;
		
		ICC_Profile javaProfile = profile.getICC_Profile();
		if (javaProfile != null) {
			switch (javaProfile.getColorSpaceType()) {
				case ColorSpace.TYPE_RGB:
					bufferedImageType = transparency ? BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR;
					break;
				case ColorSpace.TYPE_GRAY:
					bufferedImageType = BufferedImage.TYPE_BYTE_GRAY;
					break;					
				case ColorSpace.TYPE_2CLR:
				case ColorSpace.TYPE_3CLR:
				case ColorSpace.TYPE_4CLR:
				case ColorSpace.TYPE_5CLR:
				case ColorSpace.TYPE_6CLR:
				case ColorSpace.TYPE_7CLR:
				case ColorSpace.TYPE_8CLR:
				case ColorSpace.TYPE_9CLR:
				case ColorSpace.TYPE_ACLR:
				case ColorSpace.TYPE_BCLR:
				case ColorSpace.TYPE_CCLR:
				case ColorSpace.TYPE_DCLR:
				case ColorSpace.TYPE_ECLR:
				case ColorSpace.TYPE_FCLR:
				case ColorSpace.TYPE_CMY:
				case ColorSpace.TYPE_CMYK:
				case ColorSpace.TYPE_HLS:
				case ColorSpace.TYPE_HSV:
				case ColorSpace.TYPE_Lab:
				case ColorSpace.TYPE_Luv:
				case ColorSpace.TYPE_XYZ:
				case ColorSpace.TYPE_YCbCr:
				case ColorSpace.TYPE_Yxy:
                    break;
			}
		}
		return bufferedImageType;
	}
	
	/**
	 * Checks if an ICC profile type is compatible with an image. For instance, an RGB image
	 * fits an RGB ICC profile, but not a CMYK one.
	 *  
	 * @param image Image to analyze
	 * @param profile Profile to check
	 * @return <code>true</code> if the profile is valid for this type of image.
	 */
	private static boolean isProfileValid(BufferedImage image, IccProfile profile) {
		boolean profileValid = false;
		// Get profile and image types
		ICC_Profile javaProfile = profile.getICC_Profile();
		if (javaProfile == null) {
			return false;
		}
		int profileType = javaProfile.getColorSpaceType();
		int imageType = image.getType();
		// Check
		switch (imageType) {
			case BufferedImage.TYPE_3BYTE_BGR:
			case BufferedImage.TYPE_4BYTE_ABGR:
			case BufferedImage.TYPE_4BYTE_ABGR_PRE:
			case BufferedImage.TYPE_INT_ARGB:
			case BufferedImage.TYPE_INT_ARGB_PRE:
			case BufferedImage.TYPE_INT_BGR:
			case BufferedImage.TYPE_INT_RGB:
			case BufferedImage.TYPE_USHORT_555_RGB:
			case BufferedImage.TYPE_USHORT_565_RGB:
				profileValid = (profileType == ColorSpace.TYPE_RGB);
				break;
			case BufferedImage.TYPE_BYTE_GRAY:
			case BufferedImage.TYPE_USHORT_GRAY:
                profileValid = (profileType == ColorSpace.TYPE_GRAY);
				break;
			case BufferedImage.TYPE_BYTE_BINARY:
			case BufferedImage.TYPE_BYTE_INDEXED:
			case BufferedImage.TYPE_CUSTOM:
                break;
		}
		return profileValid;
	}

	/**
	 * Transforms YCCK raster data to non-inverted CMYK data.
	 * 
	 * @param ycck YCCK raster data
	 * @param inverted True if YCCK is stored as inverted values
	 */
	private static void convertYcckToCmyk(byte[] ycck, boolean inverted) {
		int numPixels = ycck.length / 4;
		
		for (int i=0; i<numPixels; i++) {
			double y  = ((int)ycck[4*i]) & 0xFF;
			double cb = ((int)ycck[4*i+1]) & 0xFF;
			double cr = ((int)ycck[4*i+2]) & 0xFF;
			int black = ((int)ycck[4*i+3]) & 0xFF;
			
			if (inverted) {
				black = 255 - black;
			}

			double red   = y + 1.402*(cr - 128.0);
			double green = y - 0.34414*(cb - 128.0) - 0.71414*(cr - 128.0);
			double blue  = y + 1.772*(cb - 128.0);

			int cyan    = clip8bit(inverted ? red : 255.0 - red);
			int magenta = clip8bit(inverted ? green : 255.0 - green);
			int yellow  = clip8bit(inverted ? blue : 255.0 - blue);
			
			ycck[4*i]   = (byte)(cyan & 0xFF);
			ycck[4*i+1] = (byte)(magenta & 0xFF);;
			ycck[4*i+2] = (byte)(yellow & 0xFF);;
			ycck[4*i+3] = (byte)(black & 0xFF);;
		}		
	}

	/**
	 * Transforms YCbCr raster data to RGB data.
	 * 
	 * @param ycbcr YCbCr raster data
	 */
	private static void convertYCbCrToRGB(byte[] ycbcr) {
		int numPixels = ycbcr.length / 3;
		
		for (int i=0; i<numPixels; i++) {
			double y  = ((int)ycbcr[3*i]) & 0xFF;
			double cb = ((int)ycbcr[3*i+1]) & 0xFF;
			double cr = ((int)ycbcr[3*i+2]) & 0xFF;
			
			int red   = clip8bit(y + 1.402*(cr - 128.0));
			int green = clip8bit(y - 0.34414*(cb - 128.0) - 0.71414*(cr - 128.0));
			int blue  = clip8bit(y + 1.772*(cb - 128.0));
			
			ycbcr[3*i]   = (byte)(red & 0xFF);
			ycbcr[3*i+1] = (byte)(green & 0xFF);
			ycbcr[3*i+2] = (byte)(blue & 0xFF);
		}
	}
	
	/**
	 * Clips a value to fit inside the integer range [0..255]
	 * 
	 * @param value The value to clip
	 * @return Clipped value inside the integer range [0..255]
	 */
	private static int clip8bit(double value) {
		return (int) Math.max(0, Math.min(255, Math.round(value)));
	}

	/**
	 * Get JPEG compression quality to be used for output files.
	 * 
	 * @return JPEG compression quality (0 to 1)
	 */
	public float getJpegQuality() {
		return m_jpegQuality;
	}

	/**
	 * Set JPEG compression quality to be used for output files.
	 * @param jpegQuality JPEG compression quality (0 to 1)
	 */
	public void setJpegQuality(float jpegQuality) {
		m_jpegQuality = Math.max(0, Math.min(1, jpegQuality));
	}
}
