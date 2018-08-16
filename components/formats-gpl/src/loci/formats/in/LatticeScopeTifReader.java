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

	private final static Pattern FILENAME_DATE_PATTERN = Pattern.compile(".*_ch.*_stack.*_.*nm_.*msec_.*msecAbs.tif");
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
	/* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
	@Override
	public boolean isThisType(RandomAccessInputStream stream) throws IOException {
		
		    TiffParser tp = new TiffParser(stream);
		    String comment = tp.getComment();
		    if (comment == null) return false;
		    System.out.println("Comment: "+comment);
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




	

	/** Populates the metadata hashtable and metadata store. */
	@Override
	protected void initMetadata() throws FormatException, IOException {
		initStandardMetadata();
		initMetadataStore();
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
