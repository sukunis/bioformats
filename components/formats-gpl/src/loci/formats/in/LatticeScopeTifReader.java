package loci.formats.in;

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
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
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

	/** Name of experiment metadata file */
	private String metaDataFile;

	private Location experimentDir;
	private String experimentName;

	/** Metadata read out from companion image files**/

	/** read out from filename**/
	private String detectorModel;
	private Length excitationWavelength;
	private Double magnification;

	/** class of tags in file */
	private String tagClass;
	/** tagname of parent node in file*/
	private String parentTag;
		
	private String SETTINGS_FILE = "_Settings.txt";

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
}
