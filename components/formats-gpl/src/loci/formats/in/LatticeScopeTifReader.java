package loci.formats.in;

import loci.common.ByteArrayHandle;
import loci.formats.*;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;
import loci.formats.services.OMEXMLService;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.TiffParser;
import loci.formats.tiff.TiffRational;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.quantity.Power;
import ome.units.quantity.Time;
import ome.xml.meta.OMEXMLMetadataRoot;
import ome.xml.model.*;
import ome.xml.model.UUID;
import ome.xml.model.enums.Correction;
import ome.xml.model.enums.DetectorType;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.Immersion;
import ome.xml.model.enums.LaserType;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.NonNegativeInteger;
import ome.xml.model.primitives.PercentFraction;
import ome.xml.model.primitives.PositiveInteger;
import ome.xml.model.primitives.Timestamp;
import loci.common.RandomAccessInputStream;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.common.Location;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;


/**
 * Lattice dataset structure:
 *
 *
 * Filename structure: <expName>_<1>_<2>_<3>_<4>_<5>_<6>.tif
 * 1: optional: CamA or CamB (default CamA if not dual detector used)
 * 2: Channel number as ch<nr> for example ch0
 * 3: Stack Number (timepoint) as stack<nr> for example stack0001
 * 4: Excitation wavelength: <val>nm for example 405nm
 * 5: acquisition time point: <val>msec for example 1199922msec
 * 6: absolut acquisition timepoint (null at start of acq software):<val>msec
 * @author Kunis
 *
 */
public class LatticeScopeTifReader extends BaseTiffReader {
	/** Logger for this class. */
	private static final Logger LOGGER =
			LoggerFactory.getLogger(TiffReader.class);

	/** Helper readers. */
	protected MinimalTiffReader tiff;

	/** bftools option*/
	public static final String CREATE_COMPANION_KEY =
			"latticescope.createcompanion";
	public static final boolean CREATE_COMPANION_DEFAULT = true;

	/** Name of experiment metadata file */
	private String metaDataFile;

	private Location experimentDir;
	private String experimentName;

	/** Metadata read out from companion image files**/

	/** read out from filename**/
	private String detectorModel;
	private Length excitationWavelength;
	private Double magnification;
	private Map<String,LightSourceSettings> lightSrcMap;

	/** class of tags in file */
	private String tagClass;
	/** tagname of parent node in file*/
	private String parentTag;

	private OME omeXML;
		
	private String SETTINGS_FILE = "_Settings.txt";
	private String DESKEWED_DIR ="Deskewed";
	private String DECON_DIR = "GPUDecon";

	private final static Pattern FILENAME_DATE_PATTERN = Pattern.compile(".*_ch.*_stack.*_.*nm_.*msec_.*msecAbs.tif");
    private final static Pattern FILENAME_DATE_PATTERN_DESKEWED = Pattern.compile(".*_ch.*_stack.*_.*nm_.*msec_.*msecAbs_.*deskewed.*.tif");
    private final static Pattern FILENAME_DATE_PATTERN_DECON = Pattern.compile(".*_ch.*_stack.*_.*nm_.*msec_.*msecAbs_.*decon.*.tif");
	/**Search for <***** ***** ***** hier irgendwas ***** ***** *****> */
	private final static Pattern HEADER_PATTERN = Pattern.compile("[*]{5}\\s+[*]{5}\\s+[*]{5}.*[*]{5}\\s+[*]{5}\\s+[*]{5}.*");
	// -- Constructor --

	/** Constructs a new LatticeScope TIFF reader. */
	public LatticeScopeTifReader() {
		super("Lattice Scope (TIF)", new String[] {"tif"});
//		this.hasCompanionFiles=true;
		suffixSufficient = false;
		domains = new String[] {FormatTools.LM_DOMAIN};
		datasetDescription =
				"One or more .tif files, and a metadata *Settings.txt file";
		parentTag="";
		tagClass="";
	}


	// -- IFormatReader API methods --
	/* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
	@Override
	public boolean isThisType(RandomAccessInputStream stream) throws IOException {
		TiffParser tp = new TiffParser(stream);
		String comment = tp.getComment();
		if (comment == null) return false;
		comment = comment.trim();
		return comment.startsWith("ImageJ=");
	}

	/* @see loci.formats.IFormatReader#isThisType(String, boolean) */
	@Override
	public boolean isThisType(String name, boolean open) {
		if (!open) return false; // not allowed to touch the file system

		if(!checkFileNamePattern(name,FILENAME_DATE_PATTERN)) {
			// also deskewed or decon file are "Lattice" files
			if(!checkFileNamePattern(name,FILENAME_DATE_PATTERN_DECON)){
				if(!checkFileNamePattern(name,FILENAME_DATE_PATTERN_DESKEWED)){
					LOGGER.info("No valid UOS LatticeScope file name: "+name);
					return false;
				}else{
					System.out.println("LATTICE READER:: deskewed file");
				}
			}else{
				System.out.println("LATTICE READER:: decon file");
			}
		}else{
			System.out.println("LATTICE READER:: raw file");
		}

		// check settingsfile exists
		Location settingsFile =findSettingsFile(name);
		if(!settingsFile.exists()){
			return false;
		}
		return true;
	}

	private boolean checkFileNamePattern(String name, Pattern filenameDatePattern) {
		Matcher m = filenameDatePattern.matcher(name);
		if (m.matches()) return true;

		return false;
	}

	// there is a possibility to append an option to reader call ( example zeissczi.autostitch -> ZeissCZIReader)
	public boolean createCompanionFiles(){
		MetadataOptions options = getMetadataOptions();
		if (options instanceof DynamicMetadataOptions) {
			return ((DynamicMetadataOptions) options).getBoolean(CREATE_COMPANION_KEY, CREATE_COMPANION_DEFAULT);
		}
		return CREATE_COMPANION_DEFAULT;
	}

	/**
	 * Parse filename to get additional information
	 * filename format: <experimentname>_<opt:camera>_<channel>_<stackNr>_<exitationWavelength>_<acqTimePoint>_<absAcqTimePoint>.tif
	 * for version :	v 4.02893.0012 Built on : 3/21/2016 12:20:26 PM, rev 2893   
	 * @param file
	 */
	private void parseFileNameMetaData(String file) {
		// TODO Auto-generated method stub
		LOGGER.info("## LatticeScopeReader::parseFileName()");
		boolean foundDet=false;
		String[] infos=file.split("_");
		for(int i=0; i<infos.length;i++) {
			// read out number of channels
			if(infos[i].contains("CamB")) {
				detectorModel="CamB";
				foundDet=true;
			}
			//find ch index
			else if(infos[i].contains("ch")) {
				//channelNumber=infos[i].substring(2, infos[i].length());
				// cam comes before channel
				if(!foundDet) {
					detectorModel="CamA";
				}
				//exc wavelenght: ch_index+2
				excitationWavelength=FormatTools.getExcitationWavelength(Double.valueOf(infos[i+2].substring(0, infos[i+2].length()-2)));
				//acq timepoint: ch_index+3
			}
		}

	}

	/** Populates the metadata hashtable and metadata store. */
	@Override
	protected void initMetadata() throws FormatException, IOException {
		initStandardMetadata();

		initMetadataStore();
	}

	private void initStandardMetadataFromFile(MetadataStore store)  {
		put("File Data","");
		if(metaDataFile!=null) {
			System.out.println("LatticeScopeTifReader:: read metadata from file: "+metaDataFile);
			try(BufferedReader br = new BufferedReader(new FileReader(metaDataFile))) {
				for(String line; (line = br.readLine()) != null; ) {
					int index=line.indexOf(":");
					String t= (tagClass.isEmpty()?"":"["+tagClass+"]::") + (parentTag.equals("")?"":parentTag+"::");
					if(index!=-1) {
						scanForData(line,index,store);
						put(t+line.substring(0, index),line.substring(index+1, line.length()));
					}else {
						index=line.indexOf("=");
						if(index!=-1) {
							scanForData(line,index,store);
							put(t+line.substring(0, index),line.substring(index+1, line.length()));
						}else {
							if(line.length()>0) {
								Matcher m = HEADER_PATTERN.matcher(line);
								if (m.matches()) {
									String[] arr = line.split("\\*\\*\\*\\*\\* \\*\\*\\*\\*\\* \\*\\*\\*\\*\\*");
									for (String a : arr) {
										if(!a.replaceAll("\\s+","").isEmpty()) {
											tagClass=a.trim();
										}
									}
								}else {
									parentTag=line.substring(line.indexOf("["), line.lastIndexOf("]")+1);
								}
							}
						}
					}
				}
				// line is not visible here.
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

	}


	private void scanForData(String line, int index, MetadataStore store) {
		String key = line.substring(0, index);
		String value = line.substring(index+1, line.length());
		if(store!=null) {
			switch(key) {
			case "Z motion ":
				store.setImageDescription(value.trim(), 0);
				break;
			case "Magnification ":
				store.setObjectiveCalibratedMagnification(Double.valueOf(value), 0, 0);
				magnification=Double.valueOf(value);
				break;
			case "Date ":
				SimpleDateFormat sfdate = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss a");
				sfdate.setTimeZone(TimeZone.getTimeZone("GMT"));
				Date date = new Date();
				try {
					date = sfdate.parse(value);
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				store.setImageAcquisitionDate(new Timestamp(new DateTime(date)), 0);
				break;
			
			default:
				//				System.out.println("NO Key FOUND");
			}

			if(key.contains("Excitation Filter, Laser, Power (%), Exp(ms)")) {
				String[] val=value.split("\t");
				if(val[2].equals(excitationWavelength)) {
					store.setChannelLightSourceSettingsAttenuation(new PercentFraction(Float.valueOf(val[3])), 0, 0);
				}
			}
			
		}

	}


	/**
	 * Populates the metadata store using the data parsed in
	 * {@link #initStandardMetadata()} along with some further parsing done in
	 * the method itself.
	 *
	 * All calls to the active <code>MetadataStore</code> should be made in this
	 * method and <b>only</b> in this method. This is especially important for
	 * sub-classes that override the getters for pixel set array size, etc.
	 */
	protected void initMetadataStore() throws FormatException {
		LOGGER.info("initMetadataStore(): Populating OME metadata");

		// the metadata store we're working with
		MetadataStore store = makeFilterMetadata();

		IFD firstIFD = ifds.get(0);
		IFD exif = null;

		if (ifds.get(0).containsKey(IFD.EXIF)) {
			try {
				IFDList exifIFDs = tiffParser.getExifIFDs();
				if (exifIFDs.size() > 0) {
					exif = exifIFDs.get(0);
				}
				tiffParser.fillInIFD(exif);
			}
			catch (IOException e) {
				LOGGER.debug("Could not read EXIF IFDs", e);
			}
		}

		MetadataTools.populatePixels(store, this, exif != null);

		// populate Image
		if (getMetadataOptions().getMetadataLevel() != MetadataLevel.MINIMUM) {
			// populate Experimenter
			String artist = firstIFD.getIFDTextValue(IFD.ARTIST);

			if (artist != null) {
				String firstName = null, lastName = null;
				int ndx = artist.indexOf(' ');
				if (ndx < 0) lastName = artist;
				else {
					firstName = artist.substring(0, ndx);
					lastName = artist.substring(ndx + 1);
				}
				String email = firstIFD.getIFDStringValue(IFD.HOST_COMPUTER);
				store.setExperimenterFirstName(firstName, 0);
				store.setExperimenterLastName(lastName, 0);
				store.setExperimenterEmail(email, 0);
				store.setExperimenterID(MetadataTools.createLSID("Experimenter", 0), 0);
			}

			//			store.setImageDescription(firstIFD.getComment(), 0);
			LOGGER.info("## LatticeScopeTifReader:: ImageDesc: "+firstIFD.getComment());
			// set the X and Y pixel dimensions

//			double pixX = firstIFD.getXResolution();
//			double pixY = firstIFD.getYResolution();

//			String unit = getResolutionUnitFromComment(firstIFD);

//			Length sizeX = FormatTools.getPhysicalSizeX(pixX, unit);
//			Length sizeY = FormatTools.getPhysicalSizeY(pixY, unit);
//
//			if (sizeX != null) {
//				store.setPixelsPhysicalSizeX(sizeX, 0);
//			}
//			if (sizeY != null) {
//				store.setPixelsPhysicalSizeY(sizeY, 0);
//			}
			store.setPixelsPhysicalSizeX(new Length((103.5/1000), UNITS.MICROMETER), 0);
			store.setPixelsPhysicalSizeY(new Length((103.5/1000), UNITS.MICROMETER), 0);
			store.setPixelsPhysicalSizeZ(null, 0);

			if (exif != null) {
				if (exif.containsKey(IFD.EXPOSURE_TIME)) {
					Object exp = exif.get(IFD.EXPOSURE_TIME);
					if (exp instanceof TiffRational) {
						Time exposure = new Time(((TiffRational) exp).doubleValue(), UNITS.SECOND);
						System.out.println("Exposure Time in tiff: "+exposure.value());
						for (int i=0; i<getImageCount(); i++) {
							store.setPlaneExposureTime(exposure, 0, i);
						}
					}
				}
			}
		}



		// link Instrument and Image
		String instrumentID = MetadataTools.createLSID("Instrument", 0);
		store.setInstrumentID(instrumentID, 0);
		store.setImageInstrumentRef(instrumentID, 0);

		store.setChannelID(MetadataTools.createLSID("Channel", 0), 0, 0);

		setDetector(store,detectorModel);
		setLightSource(store,excitationWavelength);
		setObjective(store);
		initStandardMetadataFromFile(store);


		store.setChannelExcitationWavelength(excitationWavelength, 0, 0);
		//store.setChannelName(channelNumber, 0, 0);

	}
	private Instrument setObjective(Instrument instr) {

		Objective obj1 = new Objective();
		obj1.setID(MetadataTools.createLSID("Objective", 0, 0));
		obj1.setModel("CFI-75 Apo 25x W MP");
		obj1.setManufacturer("Nikon");
		obj1.setLensNA(1.1);
		obj1.setImmersion(Immersion.WATERDIPPING);
		obj1.setCorrection(Correction.PLANAPO);
		obj1.setWorkingDistance(new Length(2, UNITS.MILLIMETER));

		Objective obj2 = new Objective();
		obj2.setID(MetadataTools.createLSID("Objective", 1, 0));
		obj2.setModel("54-10-7@488-910");
		obj2.setManufacturer("Special Optics");
		obj2.setLensNA(28.6);
		obj2.setImmersion(Immersion.WATERDIPPING);
		obj2.setWorkingDistance(new Length(3.74, UNITS.MILLIMETER));

		instr.addObjective(obj1);
		instr.addObjective(obj2);

		return instr;
	}


	private void setObjective(MetadataStore store) {
		String objID = MetadataTools.createLSID("Objective", 0, 0);

		store.setObjectiveID(objID, 0, 0);
		store.setObjectiveSettingsID(objID, 0);
		store.setObjectiveModel("CFI-75 Apo 25x W MP", 0, 0);
		store.setObjectiveManufacturer("Nikon", 0, 0);
		store.setObjectiveLensNA(1.1, 0, 0);
		store.setObjectiveImmersion(Immersion.WATERDIPPING, 0, 0);
		store.setObjectiveCorrection(Correction.PLANAPO, 0, 0);
		store.setObjectiveWorkingDistance(new Length(2, UNITS.MILLIMETER), 0, 0);

		objID = MetadataTools.createLSID("Objective", 1, 0);
		store.setObjectiveID(objID, 0, 1);
		//        store.setObjectiveSettingsID(objID, 0);
		store.setObjectiveModel("54-10-7@488-910", 0, 1);
		store.setObjectiveManufacturer("Special Optics", 0, 1);
		store.setObjectiveCalibratedMagnification(28.6, 0, 1);
		store.setObjectiveLensNA(0.66, 0, 1);
		store.setObjectiveImmersion(Immersion.WATERDIPPING, 0, 1);
		store.setObjectiveWorkingDistance(new Length(3.74, UNITS.MILLIMETER), 0, 1);
	}



	private Laser createLightSource(Length excitationWavelength,int index) {
		String lID=MetadataTools.createLSID("LightSource", 0, index);
		Laser laser = new Laser();
		laser.setID(lID);

		if(excitationWavelength!=null) {
			switch(String.valueOf(excitationWavelength.value())) {
				case "405.0":
					laser = setLaserProperties(laser,"LBX-405-300-CSB-PP","Oxxius",
							LaserType.SEMICONDUCTOR,new Length(405, UNITS.NANOMETER),
							new Power(300, UNITS.MEGAWATT));
					break;
				case "445.0":
					laser = setLaserProperties(laser,"LBX-405-300-CSB-PP","Oxxius",
							LaserType.SEMICONDUCTOR,new Length(405, UNITS.NANOMETER),
							new Power(300, UNITS.MEGAWATT));
					break;
				case "488.0":
					laser = setLaserProperties(laser,"2RU-VFL-P-300-488-B1R","MPB Communications",
							LaserType.OTHER,new Length(488, UNITS.NANOMETER),
							new Power(300, UNITS.MEGAWATT));
					break;
				case "532.0":
					laser = setLaserProperties(laser,"2RU-VFL-P-500-532-B1R","MPB Communications",
							LaserType.OTHER,new Length(532, UNITS.NANOMETER),
							new Power(500, UNITS.MEGAWATT));
					break;
				case "560.0":
					laser = setLaserProperties(laser,"2RU-VFL-P-2000-560-B1R","MPB Communications",
							LaserType.OTHER,new Length(560, UNITS.NANOMETER),
							new Power(2000, UNITS.MEGAWATT));
					break;
				case "589.0":
					laser = setLaserProperties(laser,"2RU-VFL-P-500-589-B1R","MPB Communications",
							LaserType.OTHER,new Length(589, UNITS.NANOMETER),
							new Power(500, UNITS.MEGAWATT));
					break;
				case "642.0":
					laser = setLaserProperties(laser,"2RU-VFL-P-2000-642-B1R","MPB Communications",
							LaserType.OTHER,new Length(642, UNITS.NANOMETER),
							new Power(2000, UNITS.MEGAWATT));
					break;
			}
		}
		return laser;
	}

	private void setLightSource(MetadataStore store,Length excitationWavelength) {
		String lID=MetadataTools.createLSID("LightSource", 0, 0);
		Laser laser = createLightSource(excitationWavelength,0);

		store.setLaserID(laser.getID(), 0, 0);
		store.setChannelLightSourceSettingsID(laser.getID(), 0, 0);

		if(excitationWavelength!=null) {
			store.setLaserModel(laser.getModel(), 0, 0);
			store.setLaserManufacturer(laser.getManufacturer(), 0, 0);
			store.setLaserType(laser.getType(), 0,0);
			store.setLaserWavelength(laser.getWavelength(), 0, 0);
			store.setLaserPower(laser.getPower(), 0, 0);
		}
	}

	private Laser setLaserProperties(Laser laser,String model,String manufac,LaserType type,Length wavelength,Power power){
		laser.setModel(model);
		laser.setManufacturer(manufac);
		laser.setType(type);
		laser.setWavelength(wavelength);
		laser.setPower(power);

		return laser;
	}


	/**
	 * Set predefined UOS detector 
	 * @param store 
	 */
	private void setDetector(MetadataStore store,String detectorModel) {
		Detector detector = createDetector(detectorModel,0);
		store.setDetectorID(detector.getID(), 0, 0);

		store.setDetectorModel(detector.getModel(), 0, 0);
		store.setDetectorManufacturer(detector.getManufacturer(), 0, 0);
		store.setDetectorType(detector.getType(), 0, 0);
	}

	/**
	 * Set predefined UOS detector
	 * @param detectorModel
	 */
	private Detector createDetector(String detectorModel,int index) {
		String detectorID = MetadataTools.createLSID("Detector", 0, index);

		Detector detector = new Detector();
		detector.setID(detectorID);

		if(detectorModel !=null && detectorModel.equals("CamA")) {
			detector.setModel("ORCAFlash 4.0 V2");
			detector.setManufacturer("Hamamatsu");
			detector.setType(DetectorType.CMOS);
		}else if(detectorModel!=null && detectorModel.equals("CamB")) {
			detector.setModel("ORCAFlash 4.0 V3");
			detector.setManufacturer("Hamamatsu");
			detector.setType(DetectorType.CMOS);
		}
		return detector;
	}


	/**
	 * Extracts the resolution unit symbol from the comment field
	 * 
	 * @param ifd
	 *          The {@link IFD}
	 * @return The unit symbol or <code>null</code> if the information is not
	 *         available
	 */
	private String getResolutionUnitFromComment(IFD ifd) {
		String comment = ifd.getComment();
		if (comment != null && comment.trim().length() > 0) {
			String p = "(.*)unit=(\\w+)(.*)";
			Pattern pattern = Pattern.compile(p, Pattern.DOTALL);
			Matcher m = pattern.matcher(comment);
			if (m.matches()) {
				return m.group(2);
			}
		}
		return null;
	}


	private Location findSettingsFile(String id){
		parseExperimentName(id);
		return new Location(experimentDir,experimentName+SETTINGS_FILE);
	}

	/* @see loci.formats.FormatReader#initFile(String) */
	@Override
	protected void initFile(String id) throws FormatException, IOException {
		super.initFile(id);
		Location settingsFile = null;

		settingsFile =findSettingsFile(id);
		if(!settingsFile.exists()){
			System.out.println("LATTICE READER:: can not find settingsfile at "+settingsFile.getAbsolutePath());
			throw new FormatException("Could not find settings file for lattice experiment.");
		}else{
			metaDataFile=settingsFile.getAbsolutePath();
		}

		CoreMetadata m = core.get(0,0);
		// correct T and Z dimension
		int sizeT= m.sizeT;
		int sizeZ=m.sizeZ;
		m.sizeT=sizeZ;
		m.sizeZ=sizeT;

		parseFileNameMetaData(id);
		initMetadata();

		generateCompanionFiles();
	}

	private void generateCompanionFiles() throws IOException, FormatException {
		Location deconFPath = new Location(experimentDir,DECON_DIR);
		Location deskewedFPath = new Location(experimentDir,DESKEWED_DIR);

		generateCompanionXML(experimentDir,true);
		if( deskewedFPath.exists()){
			generateCompanionXML(deskewedFPath,false);
		}
		if( deconFPath.exists()){
			generateCompanionXML(deconFPath,false);
		}
	}

	private void generateCompanionXML(Location dir, boolean raw) throws IOException, FormatException {
		List<String> ext=new ArrayList<>();
		String compName=experimentName;
		if(raw){
			ext.add(experimentName);
		}else {
			ext =getAvailableFileExtensions(dir, experimentName);
		}
		for(String myExt:ext) {
			System.out.println("LATTICE READER: working on "+myExt);
			String compNameNew = myExt;
			if(!compName.equals(myExt)){
				compNameNew = compName+"_"+myExt.substring(0,myExt.lastIndexOf(".tif"));
			}
			Location compFile = new Location(dir, compNameNew+".companion.ome");
			if(compFile.exists()) {
				System.out.println("LATTICE READER: generateCompanionFile() file still exists " + dir.getAbsolutePath());
				return;
			}
			File[] files = findCompanionFiles(dir,myExt);

			if(files !=null && files.length>0) {
				Map<Integer, Map<Integer, String>> dimensionMap = parseCompanionExperimentFileNames(files);
				createCompanionFile(compFile, dimensionMap);
				//super.initFile(compFile.getAbsolutePath());
			}
		}
	}

	private Image makeImage(int index, Map<Integer,Map<Integer,String>> dimensionMap ) {

		// get first image to read out core metadata
		CoreMetadata m = core.get(0,0);
		lightSrcMap = new HashMap<>();
		String myDetectorModel=null;
		// Create <Image/>
		Image image = new Image();
		image.setID("Image:" + index);
		// Create <Pixels/>
		Pixels pixels = new Pixels();
		pixels.setID("Pixels:" + index);
		pixels.setSizeX(new PositiveInteger(m.sizeX));
		pixels.setSizeY(new PositiveInteger(m.sizeY));
		pixels.setSizeZ(new PositiveInteger(m.sizeZ));

		if(dimensionMap!=null && dimensionMap.size()>0) {
			pixels.setSizeC(new PositiveInteger(dimensionMap.size()));
			if(dimensionMap.get(0)!=null && dimensionMap.get(0).size()>0) {
				pixels.setSizeT(new PositiveInteger(dimensionMap.get(0).size()));
			}
		}else {
			pixels.setSizeC(new PositiveInteger(1));
			pixels.setSizeT(new PositiveInteger(1));
		}

		pixels.setPhysicalSizeX(new Length((103.5/1000), UNITS.MICROMETER));
		pixels.setPhysicalSizeY(new Length((103.5/1000), UNITS.MICROMETER));
		pixels.setPhysicalSizeZ(null);
		pixels.setDimensionOrder(DimensionOrder.XYZCT);
		pixels.setType(PixelType.UINT16);


		if(dimensionMap!=null && !dimensionMap.isEmpty()) {
			// Create <TiffData/>
			for(int ch =0; ch<dimensionMap.size();ch++) {
				Map<Integer,String> timePoints=dimensionMap.get(ch);
				if(timePoints!=null && timePoints.size()>0) {
					//		    // Create <Channel/> under <Pixels/>
					Channel channel = new Channel();
					channel.setID("Channel:" + ch);
					channel.setName("ch" + ch);

					for (int t = 0; t < timePoints.size(); t++) {
						if(timePoints.get(t)!=null) {
							TiffData tiffData = new TiffData();
							tiffData.setFirstC(new NonNegativeInteger(ch));
							tiffData.setFirstT(new NonNegativeInteger(t));
							// Create <UUID/>
							UUID uuid = new UUID();
							uuid.setFileName(timePoints.get(t));

							tiffData.setUUID(uuid);
							pixels.addTiffData(tiffData);

							String excitationW = parseExcitationWavelength(timePoints.get(t));

							if(excitationW!=null && !lightSrcMap.containsKey(excitationW)){
								Length excitationW_val = new Length(Double.valueOf(excitationW),UNITS.MILLIMETER);
								LightSource lSrc= createLightSource(excitationW_val,ch);
								if(lSrc!=null) {
									omeXML.getInstrument(index).addLightSource(lSrc);
									LightSourceSettings lSett = new LightSourceSettings();
									lSett.setID(lSrc.getID());
									channel.setLightSourceSettings(lSett);
									channel.setExcitationWavelength(excitationW_val);
									lightSrcMap.put(excitationW,lSett);
								}
							}

							if(myDetectorModel ==null) {
								myDetectorModel = parseDetectorModel(timePoints.get(t));
							}
						}

					}
					if(myDetectorModel!=null) {
						Detector det = createDetector(myDetectorModel,ch);
						omeXML.getInstrument(index).addDetector(det);
						DetectorSettings dSett = new DetectorSettings();
						dSett.setID(det.getID());
						channel.setDetectorSettings(dSett);
					}
					pixels.addChannel(channel);
				}
			}
		}

		image.setPixels(pixels);
		image.linkInstrument(omeXML.getInstrument(index));

		setObjective(omeXML.getInstrument(index));
		ObjectiveSettings objSett = new ObjectiveSettings();
		objSett.setID(omeXML.getInstrument(index).getObjective(0).getID());
		image.setObjectiveSettings(objSett);

		return image;
	}
	private void createCompanionFile(Location compFile,Map<Integer, Map<Integer, String>> dimensionMap) throws FormatException, IOException{
		System.out.println("Create file: "+compFile.getAbsolutePath());
		File companionOMEFile = new File(compFile.getAbsolutePath());
		companionOMEFile.createNewFile();

		try {
			omeXML = new OME();
			Instrument instr = new Instrument();
			instr.setID(MetadataTools.createLSID("Instrument", 0));
			omeXML.addInstrument(instr);
			omeXML.addImage(makeImage(0, dimensionMap));
			//omeXML = addAdditionalMetaData(omeXML);

			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder parser = factory.newDocumentBuilder();
			Document document = parser.newDocument();
			// Produce a valid OME DOM element hierarchy
			Element root = omeXML.asXMLElement(document);
			root.setAttribute("xmlns", "http://www.openmicroscopy.org/Schemas/OME/2016-06");
			root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
			root.setAttribute("xsi:schemaLocation", "http://www.openmicroscopy.org/Schemas/OME/2016-06" +
					" " + "http://www.openmicroscopy.org/Schemas/OME/2016-06/ome.xsd");
			document.appendChild(root);

			// Write the OME DOM to the requested file
			OutputStream stream = new FileOutputStream(companionOMEFile);
			stream.write(docAsString(document).getBytes());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	/**
	 * check companion channel and stack files in the directory
	 * @param files
	 * @return map of (chNr, map(stackNr,FName))
	 */
	private Map<Integer, Map<Integer, String>> parseCompanionExperimentFileNames(File[] files)
	{
		Map<Integer,Map<Integer,String>> tiffDataFNames=new HashMap<>();

		for(int indexF=0; indexF<files.length; indexF++)
		{
			String fname=files[indexF].getName();
			if(fname!=null && !fname.contains("Settings"))
			{
				String infos=fname.substring(fname.lastIndexOf(experimentName)+experimentName.length()+1,fname.length());

				// read out number of channels _chxx
				if (infos.contains("_ch")) {
					String substringCH = infos.substring(infos.indexOf("_ch")+1, infos.length());
					String channelName = substringCH.substring(2, substringCH.indexOf("_"));
					if (channelName != null && !channelName.isEmpty()) {
						Map<Integer, String> chMap = tiffDataFNames.get(Integer.valueOf(channelName));
						if (chMap == null) {
							chMap = new HashMap<>();
						}
						//read out number of stacks _stackxxxx
						if (infos.contains("_stack")) {
							String substringST = infos.substring(infos.indexOf("_stack")+1, infos.length());
							String stackNumber = substringST.substring(5, substringST.indexOf("_"));
							if (stackNumber != null && !stackNumber.isEmpty()) {
								chMap.put(Integer.valueOf(stackNumber), fname);
								tiffDataFNames.put(Integer.valueOf(channelName), chMap);
							}
						}
					}
				}
			}
		}

		return tiffDataFNames;
	}

	/**
	 * Set path to file *_Settings.txt
	 */
	private File[] findCompanionFiles(Location dir,String filterName) {
		//TODO: that can be nicer implement
		File[] files = new File(dir.getAbsolutePath()).listFiles(new ImageFileFilter(filterName));
		return files;
	}

	private List<String> getAvailableFileExtensions(Location dir,String filterName){
		File[] tiffs = new File(dir.getAbsolutePath()).listFiles(new ImageFileFilter(filterName));
		List<String> ext = new ArrayList<>();
		for(File file : tiffs) {
			String fPath = file.getName();
			String fName = fPath.substring(0,fPath.lastIndexOf("."));
			if (fName.lastIndexOf("msecAbs") + 7 < fName.length()) {
				String extension = fPath.substring(fPath.lastIndexOf("msecAbs") + 7, fPath.length());
				if (!ext.contains(extension)) {
					ext.add(extension);
				}
			}
		}
		return ext;
	}


	private String docAsString(Document document) throws TransformerException, UnsupportedEncodingException {
		TransformerFactory transformerFactory =
			      TransformerFactory.newInstance();
			    Transformer transformer = transformerFactory.newTransformer();
			    //Setup indenting to "pretty print"
			    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			    transformer.setOutputProperty(
			        "{http://xml.apache.org/xslt}indent-amount", "4");
			    Source source = new DOMSource(document);
			    ByteArrayOutputStream os = new ByteArrayOutputStream();
			    Result result = new StreamResult(new OutputStreamWriter(os, "utf-8"));
			    transformer.transform(source, result);
			    return os.toString();
	}




	private String parseExcitationWavelength(String file) {
		String[] infos=file.split("_");
		Length excitationW=null;
		for(int i=0; i<infos.length;i++) {
			if (infos[i].contains("ch")) {
				//exc wavelenght: ch_index+2
				excitationW = FormatTools.getExcitationWavelength(Double.valueOf(infos[i + 2].substring(0, infos[i + 2].length() - 2)));
				//acq timepoint: ch_index+3
			}
		}
		return String.valueOf(excitationW.value());
	}

	private String parseDetectorModel(String fileName){
		if(fileName.contains("CamA"))
			return "CamA";
		if(fileName.contains("CamB"))
			return "CamB";
		return null;
	}


	private void parseExperimentName(String id) {
		Location baseFile = new Location(id).getAbsoluteFile();
		Location parent = baseFile.getParentFile();

		// deskewed or decon file?
		if(!checkFileNamePattern(id,FILENAME_DATE_PATTERN)){
			parent = parent.getParentFile();
		}
		
		experimentDir=parent;
		experimentName=getExperimentName(baseFile.getName());
	}


	/**
	 * Get experiment name from given filename.

	 * @param baseFile
	 * @return experiment name of current file
	 */
	private String getExperimentName(String baseFile) {
		String expName=null;
		if(baseFile.contains("Cam")) {
			expName=baseFile.substring(0, baseFile.indexOf("_Cam"));
		}else {
			expName=baseFile.substring(0, baseFile.indexOf("_ch"));
		}
		return expName;
	}


	/* @see loci.formats.IFormatReader#close(boolean) */
	@Override
	public void close(boolean fileOnly) throws IOException {
		super.close(fileOnly);
		if (!fileOnly) {
//			files = null;
		}
	}

	/**
	 * A class that implements the Java FileFilter interface.
	 */
	class ImageFileFilter implements FileFilter
	{
		private final String[] okFileExtensions = new String[] {"tif"};
		private String filterName;

		public ImageFileFilter(String name) {
			filterName=name;
		}

		public boolean accept(File file)
		{
			for (String extension : okFileExtensions)
			{
				if (file.getName().toLowerCase().endsWith(extension) && file.getName().contains(filterName))
				{
					//System.out.println("ImageFileFilter:: accept: "+file.getName());
					return true;
				}
			}
			return false;
		}
	}
}
