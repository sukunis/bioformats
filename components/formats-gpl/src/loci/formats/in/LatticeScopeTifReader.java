package loci.formats.in;

import loci.formats.in.BaseTiffReader;
import loci.formats.in.LatticeScopeTifReader.ImageFileFilter;
import loci.formats.meta.MetadataStore;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.PhotoInterp;
import loci.formats.tiff.TiffCompression;
import loci.formats.tiff.TiffParser;
import loci.formats.tiff.TiffRational;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.quantity.Power;
import ome.units.quantity.Time;
import ome.xml.model.enums.Correction;
import ome.xml.model.enums.DetectorType;
import ome.xml.model.enums.Immersion;
import ome.xml.model.enums.LaserType;
import ome.xml.model.primitives.PercentFraction;
import ome.xml.model.primitives.Timestamp;
import loci.formats.CoreMetadata;
import loci.formats.FilePattern;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.common.RandomAccessInputStream;
import loci.common.DateTools;
import loci.common.Location;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.ArrayUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
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

	/** companion files**/
	private String[] files;

	private String experimentName;

	/** Metadata read out from companion image files**/
	private int numChannels=0;
	private int sizeStack=0;
	private int numDetector=0;

	/** read out from filename**/
	private String detectorModel;
	private String channelNumber;
	private Length excitationWavelength;
	private Double magnification;

	/** class of tags in file */
	private String tagClass;
	/** tagname of parent node in file*/
	private String parentTag;

	private final static Pattern FILENAME_DATE_PATTERN = Pattern.compile(".*_ch.*_stack.*_.*nm_.*msec_.*msecAbs.tif");
	/**Search for <***** ***** ***** hier irgendwas ***** ***** *****> */
	private final static Pattern HEADER_PATTERN = Pattern.compile("[*]{5}\\s+[*]{5}\\s+[*]{5}.*[*]{5}\\s+[*]{5}\\s+[*]{5}.*");
	// -- Constructor --

	/** Constructs a new LatticeScope TIFF reader. */
	public LatticeScopeTifReader() {
		super("Lattice Scope (TIF)", new String[] {"tif"});
		suffixSufficient = false;
		domains = new String[] {FormatTools.LM_DOMAIN};
		datasetDescription =
				"One or more .tif files, and a metadata .txt file";
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
		if(!checkFileNamePattern(name)) {
			LOGGER.info("No valid UOS LatticeScope file name!");
			return false;
		}
		return true;

	}
	private boolean checkFileNamePattern(String name) {
		Matcher m = FILENAME_DATE_PATTERN.matcher(name);
		if (m.matches()) return true;
		return false;
	}

	/* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
	@Override
	public String[] getUsedFiles(boolean noPixels) {
		FormatTools.assertId(currentId, true, 1);
		return noPixels ? ArrayUtils.EMPTY_STRING_ARRAY : files;
	}

	/**
	 * check companion channel and stack files in the directory
	 * @param expName
	 * @param files
	 * @param currentName
	 */
	private void parseCompanionExperimentFileNames(String currentName) 
	{
		numChannels=0;
		sizeStack=0;
		numDetector=1;// default CamA
		boolean detector2=false;
		for(String fname:files) {
			if(!fname.contains("Settings")) {
				String[] infos=fname.substring(
						fname.lastIndexOf(experimentName)+experimentName.length()+1,fname.length()).split("_");
				for(int i=0; i<infos.length;i++) {
					// read out number of channels
					if(infos[i].contains("ch")) {
						String channelName=infos[i].substring(2, infos[i].length());
						if(numChannels < Integer.valueOf(channelName))
							numChannels = Integer.valueOf(channelName);
					}
					//read out number of stacks
					else if(infos[i].contains("stack")) {
						String stackNumber=infos[i].substring(5, infos[i].length());
						if(sizeStack < Integer.valueOf(stackNumber))
							sizeStack = Integer.valueOf(stackNumber);
					}

					else if(infos[i].contains("CamB")) {
						detector2=true;
					}
				}
			}
		}

		if(detector2)
			numDetector++;

		numChannels++;

		// add to original metadata -> series data
		addSeriesMetaList("Number Channels",numChannels);
		addSeriesMetaList("Number Stacks", sizeStack);
		LOGGER.info("## LatticeScopeReader::parseCompanionExpFileName(): CH: "+numChannels+", STACKS: "+sizeStack+", Det: "+numDetector);

	}

	/**
	 * Parse filename to get additional information
	 * filename format: <experimentname>_<opt:camera>_<channel>_<stackNr>_<exitationWavelength>_<acqTimePoint>_<absAcqTimePoint>.tif
	 * for version :	v 4.02893.0012 Built on : 3/21/2016 12:20:26 PM, rev 2893   
	 * @param store
	 */
	private void parseFileName(String file) {
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
				channelNumber=infos[i].substring(2, infos[i].length());
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
		LOGGER.info("Populating OME metadata");

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

			double pixX = firstIFD.getXResolution();
			double pixY = firstIFD.getYResolution();

			String unit = getResolutionUnitFromComment(firstIFD);

			Length sizeX = FormatTools.getPhysicalSizeX(pixX, unit);
			Length sizeY = FormatTools.getPhysicalSizeY(pixY, unit);

			if (sizeX != null) {
				store.setPixelsPhysicalSizeX(sizeX, 0);
			}
			if (sizeY != null) {
				store.setPixelsPhysicalSizeY(sizeY, 0);
			}
			store.setPixelsPhysicalSizeZ(null, 0);

			if (exif != null) {
				if (exif.containsKey(IFD.EXPOSURE_TIME)) {
					Object exp = exif.get(IFD.EXPOSURE_TIME);
					if (exp instanceof TiffRational) {
						Time exposure = new Time(((TiffRational) exp).doubleValue(), UNITS.SECOND);
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

		setDetector(store);
		setLightSource(store);
		setObjective(store);
		initStandardMetadataFromFile(store);
		
		
		store.setPixelsPhysicalSizeX(new Length((103.5/1000), UNITS.MICROMETER), 0);
		store.setPixelsPhysicalSizeY(new Length((103.5/1000), UNITS.MICROMETER), 0);
			
		store.setChannelExcitationWavelength(excitationWavelength, 0, 0);
		store.setChannelName(channelNumber, 0, 0);

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


	private void setLightSource(MetadataStore store) {
		String lID=MetadataTools.createLSID("LightSource", 0, 0);
		store.setLaserID(lID, 0, 0);
		store.setChannelLightSourceSettingsID(lID, 0, 0);
		if(excitationWavelength!=null) {
			switch(String.valueOf(excitationWavelength.value())) {
			case "405.0":
				store.setLaserModel("LBX-405-300-CSB-PP", 0, 0);
				store.setLaserManufacturer("Oxxius", 0, 0);
				store.setLaserType(LaserType.SEMICONDUCTOR, 0,0);
				store.setLaserWavelength(new Length(405, UNITS.NANOMETER), 0, 0);
				store.setLaserPower(new Power(300, UNITS.MEGAWATT), 0, 0);
				break;
			case "445.0":
				store.setLaserModel("LBX-405-300-CSB-PP", 0, 0);
				store.setLaserManufacturer("Oxxius", 0, 0);
				store.setLaserType(LaserType.SEMICONDUCTOR, 0,0);
				store.setLaserWavelength(new Length(405, UNITS.NANOMETER), 0, 0);
				store.setLaserPower(new Power(300, UNITS.MEGAWATT), 0, 0);
				break;
			case "488.0":
				store.setLaserModel("2RU-VFL-P-300-488-B1R", 0, 0);
				store.setLaserManufacturer("MPB Communications", 0, 0);
				store.setLaserType(LaserType.OTHER, 0,0);
				store.setLaserWavelength(new Length(488, UNITS.NANOMETER), 0, 0);
				store.setLaserPower(new Power(300, UNITS.MEGAWATT), 0, 0);
				break;
			case "532.0":
				store.setLaserModel("2RU-VFL-P-500-532-B1R", 0, 0);
				store.setLaserManufacturer("MPB Communications", 0, 0);
				store.setLaserType(LaserType.OTHER, 0,0);
				store.setLaserWavelength(new Length(532, UNITS.NANOMETER), 0, 0);
				store.setLaserPower(new Power(500, UNITS.MEGAWATT), 0, 0);
				break;
			case "560.0":
				store.setLaserModel("2RU-VFL-P-2000-560-B1R", 0, 0);
				store.setLaserManufacturer("MPB Communications", 0, 0);
				store.setLaserType(LaserType.OTHER, 0,0);
				store.setLaserWavelength(new Length(560, UNITS.NANOMETER), 0, 0);
				store.setLaserPower(new Power(2000, UNITS.MEGAWATT), 0, 0);
				break;
			case "589.0":
				store.setLaserModel("2RU-VFL-P-500-589-B1R", 0, 0);
				store.setLaserManufacturer("MPB Communications", 0, 0);
				store.setLaserType(LaserType.OTHER, 0,0);
				store.setLaserWavelength(new Length(589, UNITS.NANOMETER), 0, 0);
				store.setLaserPower(new Power(500, UNITS.MEGAWATT), 0, 0);
				break;
			case "642.0":
				store.setLaserModel("2RU-VFL-P-2000-642-B1R", 0, 0);
				store.setLaserManufacturer("MPB Communications", 0, 0);
				store.setLaserType(LaserType.OTHER, 0,0);
				store.setLaserWavelength(new Length(642, UNITS.NANOMETER), 0, 0);
				store.setLaserPower(new Power(2000, UNITS.MEGAWATT), 0, 0);
				break;
			}
		}

	}


	/**
	 * Set predefined UOS detector 
	 * @param store 
	 */
	private void setDetector(MetadataStore store) {
		String detectorID = MetadataTools.createLSID("Detector", 0, 0);
		store.setDetectorID(detectorID, 0, 0);

		if(detectorModel !=null && detectorModel.equals("CamA")) {
			store.setDetectorModel("ORCAFlash 4.0 V2", 0, 0);
			store.setDetectorManufacturer("Hamamatsu", 0, 0);
			store.setDetectorType(DetectorType.CMOS, 0, 0);
		}else if(detectorModel!=null && detectorModel.equals("CamB")) {
			store.setDetectorModel("ORCAFlash 4.0 V3", 0, 0);
			store.setDetectorManufacturer("Hamamatsu", 0, 0);
			store.setDetectorType(DetectorType.CMOS, 0, 0);
		}

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


	/* @see loci.formats.FormatReader#initFile(String) */
	@Override
	protected void initFile(String id) throws FormatException, IOException {
		super.initFile(id);

		LOGGER.info("Populating metadata");

		CoreMetadata m = core.get(0);

		String filename = id.substring(id.lastIndexOf(File.separator) + 1);
		filename = filename.substring(0, filename.indexOf('.'));

		// look for other files in the dataset
		findCompanionFiles();
		parseCompanionExperimentFileNames(filename);
		parseFileName(filename);
		initMetadata();

	}


	private void findCompanionFiles() {
		Location baseFile = new Location(currentId).getAbsoluteFile();
		Location parent = baseFile.getParentFile();
		experimentName=getExperimentName(baseFile.getName());
		//TODO: that can be nicer implement
		File[] tiffs = new File(parent.getAbsolutePath()).listFiles(new ImageFileFilter(experimentName));
		files = new String[tiffs.length];
		int k=0;
		for(File file : tiffs) {
			String path=file.getAbsolutePath();
			if(path.contains("_Settings")) {
				metaDataFile=path;
			}
			files[k]=path;
			k++;
		}

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
			files = null;
		}
	}


	/**
	 * A class that implements the Java FileFilter interface.
	 */
	class ImageFileFilter implements FileFilter
	{
		private final String[] okFileExtensions = new String[] {"tif","_settings.txt"};
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
					return true;
				}
			}
			return false;
		}
	}
}
