package loci.formats.in;

import loci.formats.in.BaseTiffReader;
import loci.formats.in.LatticeScopeTifReader.ImageFileFilter;
import loci.formats.meta.MetadataStore;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.PhotoInterp;
import loci.formats.tiff.TiffCompression;
import loci.formats.tiff.TiffRational;
import ome.units.UNITS;
import ome.units.quantity.Length;
import ome.units.quantity.Time;
import ome.xml.model.primitives.Timestamp;
import loci.formats.CoreMetadata;
import loci.formats.FilePattern;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.common.RandomAccessInputStream;
import loci.common.DateTools;
import loci.common.Location;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.ArrayUtils;
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


	// -- Constructor --

	/** Constructs a new LatticeScope TIFF reader. */
	public LatticeScopeTifReader() {
		super("Lattice Scope (TIF)", new String[] {"tif"});
		suffixSufficient = false;
		domains = new String[] {FormatTools.LM_DOMAIN};
		datasetDescription =
				"One or more .tif files, and a metadata .txt file";
	}


	// -- IFormatReader API methods --

	/* @see loci.formats.IFormatReader#isThisType(String, boolean) */
	@Override
	public boolean isThisType(String name, boolean open) {

		if (!open) return false; // not allowed to touch the file system

//		LOGGER.info("## LaticeScopeReader:: isThisType(): "+name);
//		Location currentFile = new Location(name).getAbsoluteFile();
//		
////		FilePattern pattern = new FilePattern(currentFile);
////		System.out.println("generated pattern: "+pattern.getPattern());
//        //if (!pattern.isValid()) continue;
//		
//		
//		String cuFilename = currentFile.getName();
//
//		// check if there is an *_Settings.txt file in the same directory
//		// attention: expect only ONE experiment in one directory!!!
//		Location directory = currentFile.getParentFile();
//		
//		String[] files = directory.list(true);
//		if (files != null) {
//			for (String file : files) {
//				String fname = file;
//				if (fname.contains("_Settings")) {
//					//extract experiment name
//					String expName=fname.substring(0,fname.indexOf("_Settings"));				
//					LOGGER.info("## LaticeScopeReader:: isThisType(): check "+fname+" -> "+expName);
//
//					// *_Settings.txt and *.tif file of same experiment?
//					if (cuFilename.startsWith(expName))
//					{
//						LOGGER.info("## LatticeScopeReader::Companion Settings File: "+fname);
//						LOGGER.info("## LatticeScopeReader::Image File: "+cuFilename);
//						parseFileName(cuFilename);
//						metaDataFile=fname;
//						parseCompanionExperimentFileNames(expName,files,cuFilename);
//						return true;
//					}else{
//						LOGGER.info("## LatticeScopeReader::no match: "+fname+", "+cuFilename);
//					}
//				}
//			}
//		}
//		
//		
//		return false;

		return true;
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
			String[] infos=fname.substring(
					fname.lastIndexOf(experimentName)+experimentName.length()+1,fname.length()).split("_");
			System.out.println("PARSE INFOS FROM: "+fname.substring(fname.lastIndexOf(experimentName)+experimentName.length()+1,fname.length()));
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
		
		if(detector2)
			numDetector++;
		
		numChannels++;
		
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
				System.out.println("Detector: CamB");
				foundDet=true;
			}
			//find ch index
			else if(infos[i].contains("ch")) {
				channelNumber=infos[i].substring(2, infos[i].length());
				System.out.println("Channel: "+channelNumber);
				// cam comes before channel
				if(!foundDet) {
					detectorModel="CamA";
					System.out.println("Detector: CamA");
				}
				//exc wavelenght: ch_index+2
				excitationWavelength=FormatTools.getExcitationWavelength(Double.valueOf(infos[i+2].substring(0, infos[i+2].length()-2)));
				System.out.println("Excitation Wavelenght: "+excitationWavelength.value()+" "+excitationWavelength.unit().getSymbol());
				//acq timepoint: ch_index+3
			}
		}
		
	}




	/* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
	@Override
	public boolean isThisType(RandomAccessInputStream stream) throws IOException {
		//	   no information inside the tif file
		return false;
	}

	/** Populates the metadata hashtable and metadata store. */
	@Override
	protected void initMetadata() throws FormatException, IOException {
		initStandardMetadata();
		initMetadataStore();
	}


	/**
	 * Parses standard metadata.
	 *
	 * NOTE: Absolutely <b>no</b> calls to the metadata store should be made in
	 * this method or methods that override this method. Data <b>will</b> be
	 * overwritten if you do so.
	 */
	protected void initStandardMetadata() throws FormatException, IOException {
		if (getMetadataOptions().getMetadataLevel() == MetadataLevel.MINIMUM) {
			return;
		}

		for (int i=0; i<ifds.size(); i++) {
			put("PageName #" + i, ifds.get(i), IFD.PAGE_NAME);
		}

		IFD firstIFD = ifds.get(0);
		put("ImageWidth", firstIFD, IFD.IMAGE_WIDTH);
		put("ImageLength", firstIFD, IFD.IMAGE_LENGTH);
		put("BitsPerSample", firstIFD, IFD.BITS_PER_SAMPLE);

		// retrieve EXIF values, if available

		if (ifds.get(0).containsKey(IFD.EXIF)) {
			IFDList exifIFDs = tiffParser.getExifIFDs();
			if (exifIFDs.size() > 0) {
				IFD exif = exifIFDs.get(0);
				tiffParser.fillInIFD(exif);
				for (Integer key : exif.keySet()) {
					int k = key.intValue();
					addGlobalMeta(getExifTagName(k), exif.get(key));
				}
			}
		}

		TiffCompression comp = firstIFD.getCompression();
		put("Compression", comp.getCodecName());

		PhotoInterp photo = firstIFD.getPhotometricInterpretation();
		String photoInterp = photo.getName();
		String metaDataPhotoInterp = photo.getMetadataType();
		put("PhotometricInterpretation", photoInterp);
		put("MetaDataPhotometricInterpretation", metaDataPhotoInterp);

		putInt("CellWidth", firstIFD, IFD.CELL_WIDTH);
		putInt("CellLength", firstIFD, IFD.CELL_LENGTH);

		int or = firstIFD.getIFDIntValue(IFD.ORIENTATION);

		// adjust the width and height if necessary
		if (or == 8) {
			put("ImageWidth", firstIFD, IFD.IMAGE_LENGTH);
			put("ImageLength", firstIFD, IFD.IMAGE_WIDTH);
		}

		String orientation = null;
		// there is no case 0
		switch (or) {
		case 1:
			orientation = "1st row -> top; 1st column -> left";
			break;
		case 2:
			orientation = "1st row -> top; 1st column -> right";
			break;
		case 3:
			orientation = "1st row -> bottom; 1st column -> right";
			break;
		case 4:
			orientation = "1st row -> bottom; 1st column -> left";
			break;
		case 5:
			orientation = "1st row -> left; 1st column -> top";
			break;
		case 6:
			orientation = "1st row -> right; 1st column -> top";
			break;
		case 7:
			orientation = "1st row -> right; 1st column -> bottom";
			break;
		case 8:
			orientation = "1st row -> left; 1st column -> bottom";
			break;
		}
		put("Orientation", orientation);
		putInt("SamplesPerPixel", firstIFD, IFD.SAMPLES_PER_PIXEL);

		put("Software", firstIFD, IFD.SOFTWARE);
		put("Instrument Make", firstIFD, IFD.MAKE);
		put("Instrument Model", firstIFD, IFD.MODEL);
		put("Document Name", firstIFD, IFD.DOCUMENT_NAME);
		put("DateTime", getImageCreationDate());
		put("Artist", firstIFD, IFD.ARTIST);

		put("HostComputer", firstIFD, IFD.HOST_COMPUTER);
		put("Copyright", firstIFD, IFD.COPYRIGHT);

		put("NewSubfileType", firstIFD, IFD.NEW_SUBFILE_TYPE);

		int thresh = firstIFD.getIFDIntValue(IFD.THRESHHOLDING);
		String threshholding = null;
		switch (thresh) {
		case 1:
			threshholding = "No dithering or halftoning";
			break;
		case 2:
			threshholding = "Ordered dithering or halftoning";
			break;
		case 3:
			threshholding = "Randomized error diffusion";
			break;
		}
		put("Threshholding", threshholding);

		int fill = firstIFD.getIFDIntValue(IFD.FILL_ORDER);
		String fillOrder = null;
		switch (fill) {
		case 1:
			fillOrder = "Pixels with lower column values are stored " +
					"in the higher order bits of a byte";
			break;
		case 2:
			fillOrder = "Pixels with lower column values are stored " +
					"in the lower order bits of a byte";
			break;
		}
		put("FillOrder", fillOrder);

		putInt("Make", firstIFD, IFD.MAKE);
		putInt("Model", firstIFD, IFD.MODEL);
		putInt("MinSampleValue", firstIFD, IFD.MIN_SAMPLE_VALUE);
		putInt("MaxSampleValue", firstIFD, IFD.MAX_SAMPLE_VALUE);

		TiffRational xResolution = firstIFD.getIFDRationalValue(IFD.X_RESOLUTION);
		TiffRational yResolution = firstIFD.getIFDRationalValue(IFD.Y_RESOLUTION);

		if (xResolution != null) {
			put("XResolution", xResolution.doubleValue());
		}
		if (yResolution != null) {
			put("YResolution", yResolution.doubleValue());
		}

		int planar = firstIFD.getIFDIntValue(IFD.PLANAR_CONFIGURATION);
		String planarConfig = null;
		switch (planar) {
		case 1:
			planarConfig = "Chunky";
			break;
		case 2:
			planarConfig = "Planar";
			break;
		}
		put("PlanarConfiguration", planarConfig);

		putInt("XPosition", firstIFD, IFD.X_POSITION);
		putInt("YPosition", firstIFD, IFD.Y_POSITION);
		putInt("FreeOffsets", firstIFD, IFD.FREE_OFFSETS);
		putInt("FreeByteCounts", firstIFD, IFD.FREE_BYTE_COUNTS);
		putInt("GrayResponseUnit", firstIFD, IFD.GRAY_RESPONSE_UNIT);
		putInt("GrayResponseCurve", firstIFD, IFD.GRAY_RESPONSE_CURVE);
		putInt("T4Options", firstIFD, IFD.T4_OPTIONS);
		putInt("T6Options", firstIFD, IFD.T6_OPTIONS);

		int res = firstIFD.getIFDIntValue(IFD.RESOLUTION_UNIT);
		String resUnit = null;
		switch (res) {
		case 1:
			resUnit = "None";
			break;
		case 2:
			resUnit = "Inch";
			break;
		case 3:
			resUnit = "Centimeter";
			break;
		}
		put("ResolutionUnit", resUnit);

		putString("PageNumber", firstIFD, IFD.PAGE_NUMBER);
		putInt("TransferFunction", firstIFD, IFD.TRANSFER_FUNCTION);

		int predict = firstIFD.getIFDIntValue(IFD.PREDICTOR);
		String predictor = null;
		switch (predict) {
		case 1:
			predictor = "No prediction scheme";
			break;
		case 2:
			predictor = "Horizontal differencing";
			break;
		}
		put("Predictor", predictor);

		putInt("WhitePoint", firstIFD, IFD.WHITE_POINT);
		putInt("PrimaryChromacities", firstIFD, IFD.PRIMARY_CHROMATICITIES);

		putInt("HalftoneHints", firstIFD, IFD.HALFTONE_HINTS);
		putInt("TileWidth", firstIFD, IFD.TILE_WIDTH);
		putInt("TileLength", firstIFD, IFD.TILE_LENGTH);
		putInt("TileOffsets", firstIFD, IFD.TILE_OFFSETS);
		putInt("TileByteCounts", firstIFD, IFD.TILE_BYTE_COUNTS);

		int ink = firstIFD.getIFDIntValue(IFD.INK_SET);
		String inkSet = null;
		switch (ink) {
		case 1:
			inkSet = "CMYK";
			break;
		case 2:
			inkSet = "Other";
			break;
		}
		put("InkSet", inkSet);

		putInt("InkNames", firstIFD, IFD.INK_NAMES);
		putInt("NumberOfInks", firstIFD, IFD.NUMBER_OF_INKS);
		putInt("DotRange", firstIFD, IFD.DOT_RANGE);
		put("TargetPrinter", firstIFD, IFD.TARGET_PRINTER);
		putInt("ExtraSamples", firstIFD, IFD.EXTRA_SAMPLES);

		int fmt = firstIFD.getIFDIntValue(IFD.SAMPLE_FORMAT);
		String sampleFormat = null;
		switch (fmt) {
		case 1:
			sampleFormat = "unsigned integer";
			break;
		case 2:
			sampleFormat = "two's complement signed integer";
			break;
		case 3:
			sampleFormat = "IEEE floating point";
			break;
		case 4:
			sampleFormat = "undefined";
			break;
		}
		put("SampleFormat", sampleFormat);

		putInt("SMinSampleValue", firstIFD, IFD.S_MIN_SAMPLE_VALUE);
		putInt("SMaxSampleValue", firstIFD, IFD.S_MAX_SAMPLE_VALUE);
		putInt("TransferRange", firstIFD, IFD.TRANSFER_RANGE);

		int jpeg = firstIFD.getIFDIntValue(IFD.JPEG_PROC);
		String jpegProc = null;
		switch (jpeg) {
		case 1:
			jpegProc = "baseline sequential process";
			break;
		case 14:
			jpegProc = "lossless process with Huffman coding";
			break;
		}
		put("JPEGProc", jpegProc);

		putInt("JPEGInterchangeFormat", firstIFD, IFD.JPEG_INTERCHANGE_FORMAT);
		putInt("JPEGRestartInterval", firstIFD, IFD.JPEG_RESTART_INTERVAL);

		putInt("JPEGLosslessPredictors", firstIFD, IFD.JPEG_LOSSLESS_PREDICTORS);
		putInt("JPEGPointTransforms", firstIFD, IFD.JPEG_POINT_TRANSFORMS);
		putInt("JPEGQTables", firstIFD, IFD.JPEG_Q_TABLES);
		putInt("JPEGDCTables", firstIFD, IFD.JPEG_DC_TABLES);
		putInt("JPEGACTables", firstIFD, IFD.JPEG_AC_TABLES);
		putInt("YCbCrCoefficients", firstIFD, IFD.Y_CB_CR_COEFFICIENTS);

		int ycbcr = firstIFD.getIFDIntValue(IFD.Y_CB_CR_SUB_SAMPLING);
		String subSampling = null;
		switch (ycbcr) {
		case 1:
			subSampling = "chroma image dimensions = luma image dimensions";
			break;
		case 2:
			subSampling = "chroma image dimensions are " +
					"half the luma image dimensions";
			break;
		case 4:
			subSampling = "chroma image dimensions are " +
					"1/4 the luma image dimensions";
			break;
		}
		put("YCbCrSubSampling", subSampling);

		putInt("YCbCrPositioning", firstIFD, IFD.Y_CB_CR_POSITIONING);
		putInt("ReferenceBlackWhite", firstIFD, IFD.REFERENCE_BLACK_WHITE);

		// bits per sample and number of channels
		int[] q = firstIFD.getBitsPerSample();
		int bps = q[0];
		int numC = q.length;

		// numC isn't set properly if we have an indexed color image, so we need
		// to reset it here

		if (photo == PhotoInterp.RGB_PALETTE || photo == PhotoInterp.CFA_ARRAY) {
			numC = 3;
		}

		put("BitsPerSample", bps);
		put("NumberOfChannels", numC);
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

		// format the creation date to ISO 8601

		String creationDate = getImageCreationDate();
		String date = DateTools.formatDate(creationDate, DATE_FORMATS, ".");
		if (creationDate != null && date == null) {
			LOGGER.warn("unknown creation date format: {}", creationDate);

			//convert date
			date = DateTools.formatDate(creationDate,DATE_FORMATS_EXT);
			if(date != null){
				LOGGER.info("known uos creation date format: convert to {} ", date);
				String dateformat= DateTools.ISO8601_FORMAT_MS;
				String s=DateTools.formatDate(date,dateformat);
				if(s==null){
					dateformat=DateTools.ISO8601_FORMAT;
					s=DateTools.formatDate(date, dateformat);

				}
				DateFormat df=new SimpleDateFormat(dateformat);
				try{
					Date d=df.parse(s);
					//	    		  SimpleDateFormat f=new SimpleDateFormat(DateTools.TIMESTAMP_FORMAT);
					SimpleDateFormat f=new SimpleDateFormat(DateTools.ISO8601_FORMAT);
					System.out.println("BaseTiffReader: "+creationDate+" -> "+s+" -> "+f.format(d));

					date = f.format(d);
				}catch(Exception e){
					date=s;
				}
			}
		}
		creationDate = date;

		// populate Image

		if (creationDate != null) {
			store.setImageAcquisitionDate(new Timestamp(creationDate), 0);
		}

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

			store.setImageDescription(firstIFD.getComment(), 0);
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
		
		 // link DetectorSettings to an actual Detector
        String detectorID = MetadataTools.createLSID("Detector", 0, 0);
        store.setDetectorID(detectorID, 0, 0);
		store.setDetectorModel(detectorModel, 0, 0);
		store.setChannelExcitationWavelength(excitationWavelength, 0, 0);
		store.setChannelName(channelNumber, 0, 0);
		
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
		findCompanionTIFs();
		parseCompanionExperimentFileNames(filename);
		parseFileName(filename);
		initMetadata();
		
	}

	
	private void findCompanionTIFs() {
		Location baseFile = new Location(currentId).getAbsoluteFile();
		Location parent = baseFile.getParentFile();
		experimentName=getExperimentName(baseFile.getName());
		System.out.println("EXPERIMENT NAME: "+experimentName);



		//TODO: that can be nicer implement
		File[] tiffs = new File(parent.getAbsolutePath()).listFiles(new ImageFileFilter(experimentName));
		files = new String[tiffs.length];
		int k=0;
		for(File file : tiffs) {
			System.out.println("FILE: "+file.getAbsolutePath());
			files[k]=file.getAbsolutePath();
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
	        return true;
	      }
	    }
	    return false;
	  }
	}
}
