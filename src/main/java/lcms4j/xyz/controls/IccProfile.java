package lcms4j.xyz.controls;

import lcms4j.xyz.LCMS4J;

import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Representation of an ICC color profile.<br><br>
 * This class uses native LittleCMS C library for managing ICC profile data, and may allocate resources that
 * will not be automatically freed by JVM's garbage collection. It is mandatory to call {@link #dispose() dispose()} method
 * on any <code>IccProfile</code> object when it is no longer needed in order to free all native resources and avoid memory leaks.
 * 
 * @author Enric Tordera
 *
 */
public class IccProfile {
	/** Id for Gray D50 gamma 2.2 profile */
	public static final int PROFILE_GRAY = 1;
	/** Id for sRGB ICC profile */
	public static final int PROFILE_SRGB = 2;
	/** Id for AdobeRGB ICC profile */
	public static final int PROFILE_ADOBERGB = 3;	
	/** Id for Coated Fogra 39 ICC profile */
	public static final int PROFILE_FOGRA39 = 4;
	/** Native handle to ICC Profile data */
	private long m_hProfile = 0;
	
	/**
	 * Constructs an IccProfile object for a predefined standard ICC profile.
	 *  
	 * @param id Id for the standard ICC profile (constants <code>IccProfile.PROFILE_*</code>)
	 * @throws LCMS4JException If the standard profile can not be generated
	 */
	public IccProfile(int id) throws LCMS4JException {
		switch (id) {
			case PROFILE_GRAY:
				m_hProfile = LCMS4J.cmsCreateGrayProfile(2.2);
				break;
			case PROFILE_SRGB:
				m_hProfile = LCMS4J.cmsCreate_sRGBProfile();
				break;
			case PROFILE_ADOBERGB:
				loadFromResource("AdobeRGB1998.icc");
				break;
			case PROFILE_FOGRA39:
				loadFromResource("CoatedFOGRA39.icc");
				break;
			default:
				throw new IllegalArgumentException("Id for ICC profile must be one of IccProfile.PROFILE_* constants");
		}
		
	}
	
	/**
	 * Constructs an IccProfile object based on data contained in a file.
	 * 
	 * @param file File with ICC profile data (usually <code>.icm</code> or <code>.icc</code> extension)
	 * @throws LCMS4JException When unable to load ICC profile data from this file
	 */
	public IccProfile(File file) throws LCMS4JException {
		if (file == null) {
			throw new IllegalArgumentException("ICC profile file can not be null.");
		}
		if (!loadFromFile(file)) {
			throw new LCMS4JException("Unable to load ICC profile data from file "+file.getAbsolutePath());
		}
	}
	
	/**
	 * Constructs an IccProfile object based on data contained in a memory buffer.
	 * 
	 * @param data Memory buffer with complete data of the ICC profile
	 * @throws LCMS4JException When unable to load ICC profile data from this memory buffer
	 */
	public IccProfile(byte[] data) throws LCMS4JException {
		if (data == null) {
			throw new IllegalArgumentException("ICC profile data can not be null.");
		}
		if (!loadFromMem(data)) {
			throw new LCMS4JException("Unable to load ICC profile data from memory buffer.");
		}		
	}
	
	/**
	 * Frees any native resources allocated by the <code>IccProfile</code> object
	 */
	public void dispose() {
		close();
	}
	
	/**
	 * Loads an ICC Profile from a resource.<br>
	 * Resources will we looked for in the <code>com.gmail.etordera.LCMS4J.iccprofiles</code> package.
	 * 
	 * @param filename Filename of the resource to load as ICC profile.
	 * @throws LCMS4JException if unable to load profile from provided resource name.
	 */
	private void loadFromResource(String filename) throws LCMS4JException {
		File iccFile = new File("src/main/resources/lcms4j/xyz/iccprofiles");
		File file = new File(iccFile.getPath(), filename);
		if (file.exists()) {
			System.out.printf("ICC %s exists!", filename);
		}
		InputStream is = IccProfile.class.getResourceAsStream("/lcms4j/xyz/iccprofiles/" + filename);
		if (is == null) {
			throw new LCMS4JException("Missing "+filename+" in profile data.");
		}
		ByteArrayOutputStream os = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int bytesRead = 0;
		try {
			while ((bytesRead = is.read(buffer)) != -1) {
				os.write(buffer, 0, bytesRead);
			}
			os.flush();
			os.close();
			is.close();
		} catch (IOException e) {
			throw new LCMS4JException("Unable to read "+filename+" profile data.");
		}
		byte[] data = os.toByteArray();
		if (!loadFromMem(data)) {
			throw new LCMS4JException("Invalid "+filename+" profile data.");
		}						
	}
	
	/**
	 * Loads an ICC Profile from a file.
	 * 
	 * @param file ICC Profile file
	 * @return <code>true</code> if loaded correctly, <code>false</code> otherwise
	 */
	public boolean loadFromFile(File file) {
		close();
		m_hProfile = LCMS4J.cmsOpenProfileFromFile(file.getAbsolutePath(),"r");
		return (m_hProfile != 0);
	}
	
	/**
	 * Loads an ICC Profile from a memory buffer.
	 * 
	 * @param data ICC Profile data buffer
	 * @return <code>true</code> if loaded correctly, <code>false</code> otherwise
	 */
	public boolean loadFromMem(byte[] data) {
		close();
		m_hProfile = LCMS4J.cmsOpenProfileFromMem(data);
		return (m_hProfile != 0);
	}
	
	/**
	 * Frees any previously loaded ICC Profile data.
	 */
	private void close() {
		if (m_hProfile != 0) {
			LCMS4J.cmsCloseProfile(m_hProfile);
			m_hProfile = 0;
		}		
	}

	/**
	 * Gets the text description stored inside the ICC Profile
	 * 
	 * @return Description of the ICC Profile, or a blank string if not found
	 */
	public String getProfileInfo() {
		String name = "";
		if (m_hProfile != 0) {
			name = LCMS4J.cmsGetProfileInfoASCII(m_hProfile);
		}
		return name;
	}
	
	/**
	 * Gets the handle to native resources used by this object.
	 * 
	 * @return Handle to native resources, or <code>0</code> if no resources are reserved.
	 */
	public long getHandle() {
		return m_hProfile;
	}
	
	/**
	 * Gets a standard <code>ICC_Profile</code> object from this profile.
	 * @return A standard <code>ICC_Profile</code> object, or <code>null</code> on error
	 */
	public ICC_Profile getICC_Profile() {
		ICC_Profile result = null;
		if (m_hProfile != 0) {
			byte[] profileData = LCMS4J.cmsSaveProfileToMem(m_hProfile);
			if (profileData.length > 0) {
				result = ICC_Profile.getInstance(profileData);
			}
		}
		return result;
	}
}
