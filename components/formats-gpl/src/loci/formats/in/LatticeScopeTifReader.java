package loci.formats.in;

import loci.formats.in.BaseTiffReader;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;
import loci.formats.out.TiffWriter;
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
import ome.xml.model.Channel;
import ome.xml.model.FileAnnotation;
import ome.xml.model.Image;
import ome.xml.model.Instrument;
import ome.xml.model.OME;
import ome.xml.model.Pixels;
import ome.xml.model.StructuredAnnotations;
import ome.xml.model.TiffData;
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
import loci.common.ByteArrayHandle;
import loci.common.Location;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
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
	private List<List<String>> tiffDataFNames;
	
	private OMEXMLMetadataRoot newRoot;

	private Location experimentDir;
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
//		this.hasCompanionFiles=true;
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
			LOGGER.info("No valid UOS LatticeScope file name: "+name);
			return false;
		}
		return true;

	}
	private boolean checkFileNamePattern(String name) {
		Matcher m = FILENAME_DATE_PATTERN.matcher(name);
		if (m.matches()) return true;
		return false;
	}
	
//	 /* @see loci.formats.IFormatReader#isSingleFile(String) */
//	  @Override
//	  public boolean isSingleFile(String id) throws FormatException, IOException {
//	    return false;
//	}
//	  
//	  @Override
//	  public int fileGroupOption( final String id ) throws FormatException, IOException
//	  {
//	    return FormatTools.MUST_GROUP;
//	  }

	/* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
//	@Override
//	public String[] getUsedFiles(boolean noPixels) {
//		FormatTools.assertId(currentId, true, 1);
//		return noPixels ? ArrayUtils.EMPTY_STRING_ARRAY : new String[] {metaDataFile};//files;
//	}
	
//	/* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
//	  @Override
//	  public String[] getSeriesUsedFiles(boolean noPixels) {
//		  System.out.println("LatticeScopeReader::getSeriesUsedFiles()");
//	    FormatTools.assertId(currentId, true, 1);
//	   
//	    if(noPixels) {
//	    	return new String[] {metaDataFile};
//	    }
//
//	    final List<String> fileList = new ArrayList<String>();
//	    if (files != null) {
//	      for (String file : files) {
//	        String f = file.toLowerCase();
//	        if (!f.endsWith(".txt") )
//	        {
//	          if (!fileList.contains(file)) {
//	        	  System.out.println("series file: "+file);
//	        	  fileList.add(file);
//	          }
//	        }
//	      }
//	    }
////	    if (!noPixels) {
////	      if (getSeries() == 0 && tiffs != null) {
////	    	  fileList.addAll(tiffs);
////	      }
////	      else if (getSeries() == 1 && previewNames != null) {
////	    	  fileList.addAll(previewNames);
////	      }
////	    }
//
//	    return fileList.toArray(new String[0]);
//	  }

	/**
	 * check companion channel and stack files in the directory
	 * @param expName
	 * @param files
	 * @param currentName
	 */
	private void parseCompanionExperimentFileNames(String currentName) 
	{
		tiffDataFNames=new ArrayList<>();
		numChannels=0;
		sizeStack=0;
		numDetector=1;// default CamA
		boolean detector2=false;
		for(int indexF=0; indexF<files.length; indexF++) {
			String fname=files[indexF];
			System.out.println("Parse "+fname);
			if(fname!=null && !fname.contains("Settings")) {
				String[] infos=fname.substring(
						fname.lastIndexOf(experimentName)+experimentName.length()+1,fname.length()).split("_");
				int thisCH=-1;
				int thisStack=-1;
				for(int i=0; i<infos.length;i++) {
					
					// read out number of channels
					if(infos[i].contains("ch")) {
						String channelName=infos[i].substring(2, infos[i].length());
						thisCH=Integer.valueOf(channelName);
						if(numChannels < thisCH)
							numChannels = thisCH;
					}
					//read out number of stacks
					else if(infos[i].contains("stack")) {
						String stackNumber=infos[i].substring(5, infos[i].length());
						thisStack=Integer.valueOf(stackNumber);
						if(sizeStack < thisStack )
							sizeStack = thisStack;
					}

					else if(infos[i].contains("CamB")) {
						detector2=true;
					}
					
				}
				System.out.println("Check file infos: "+Arrays.toString(infos)+":: "+thisCH+", "+thisStack);
				//entry in tiffDataFNames list according ch and stack number
				if(thisCH>-1 && thisStack>-1) {
					try {
						List<String> st=null;
						if(thisCH <tiffDataFNames.size())
							st=tiffDataFNames.get(thisCH);
						if(st==null) {
							st=new ArrayList<>();
						}
						st.add(fname);
						
						if(thisCH < tiffDataFNames.size())
							tiffDataFNames.set(thisCH, st);
						else
							tiffDataFNames.add(st);
					}catch(Exception e) {
						System.out.println("ERROR tiffDataFNames list index: "+tiffDataFNames.size());
						e.printStackTrace();
					}
				}
			}
		}

		if(detector2)
			numDetector++;

		numChannels++;

		// add to original metadata -> series data
//		addSeriesMetaList("Number Channels",numChannels);
//		addSeriesMetaList("Number Stacks", sizeStack);
		System.out.println("## LatticeScopeReader::parseCompanionExpFileName(): CH: "+tiffDataFNames.size()+", STACKS: "+tiffDataFNames.get(0).size()+", Det: "+numDetector);

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
			
		store.setChannelExcitationWavelength(excitationWavelength, 0, 0);
		store.setChannelName(channelNumber, 0, 0);
		
//		try {
//			convertToXML(store);
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}

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

		parseExperimentName();
		//TODO: test companion.ome exists -> return;
//		Location compFile = new Location(experimentDir, experimentName+".companion.ome");
//		if(compFile.exists()) {
//			return;
//		}
		
		LOGGER.info("Populating metadata");
		System.out.println("intFile(): "+id);
		CoreMetadata m = core.get(0,0);
		
		// correct T and Z dimension
		int sizeT= m.sizeT;
		int sizeZ=m.sizeZ;
		m.sizeT=sizeZ;
		m.sizeZ=sizeT;
		
		String filename = id.substring(id.lastIndexOf(File.separator) + 1);
		filename = filename.substring(0, filename.indexOf('.'));

		// look for other files in the dataset
//		findCompanionFiles();
//		parseCompanionExperimentFileNames(filename);
		initMetadata();
		
		
//		OME omeXML=new OME();
//		omeXML.addImage(makeImage(0));
//		omeXML=addAdditionalMetaData(omeXML,makeFilterMetadata());
//		
//		System.out.println("Create file: "+compFile.getAbsolutePath());
//	    File companionOMEFile = new File(compFile.getAbsolutePath());
//	    companionOMEFile.createNewFile();
//	    System.out.println("Write test companion.ome to: "+companionOMEFile.getAbsolutePath());
//	    try {
//			
//			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
//		    DocumentBuilder parser = factory.newDocumentBuilder();
//		    Document document = parser.newDocument();
//		    // Produce a valid OME DOM element hierarchy
//		    Element root = omeXML.asXMLElement(document);
//		    root.setAttribute("xmlns", "http://www.openmicroscopy.org/Schemas/OME/2016-06");
//		    root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
//		    root.setAttribute("xsi:schemaLocation", "http://www.openmicroscopy.org/Schemas/OME/2016-06" + 
//		    " " + "http://www.openmicroscopy.org/Schemas/OME/2016-06/ome.xsd");
//		    document.appendChild(root);
//		    
//		    // Write the OME DOM to the requested file
//		    OutputStream stream = new FileOutputStream(companionOMEFile);
//		    stream.write(docAsString(document).getBytes());
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		
//
//	    
////	    System.out.println("Reading file into memory from disk...");
////	    int fileSize = (int) companionOMEFile.length();
////	    DataInputStream in = new DataInputStream(new FileInputStream(companionOMEFile));
////	    byte[] inBytes = new byte[fileSize];
////	    in.readFully(inBytes);
////	    System.out.println(fileSize + " bytes read.");
////	    
////	 // determine input file suffix
////	    String fileName = companionOMEFile.getName();
////	    int dot = fileName.lastIndexOf(".");
////	    String suffix = ".companion.ome";//dot < 0 ? "" : fileName.substring(dot);
////
////	    // map input id string to input byte array
////	    String inId = "inBytes" + suffix;
////	    Location.mapFile(inId, new ByteArrayHandle(inBytes));
//	    
//	    OMETiffReader reader = new OMETiffReader();
//	    reader.setId(compFile.getAbsolutePath());

	}

	private OME addAdditionalMetaData(OME omeXML, MetadataStore store) throws FormatException {
		if (store instanceof MetadataRetrieve) {
			try {
				ServiceFactory factory = new ServiceFactory();
				OMEXMLService service = factory.getInstance(OMEXMLService.class);
				String xml = service.getOMEXML(service.asRetrieve(store));
				OMEXMLMetadataRoot root = (OMEXMLMetadataRoot) store.getRoot();
				IMetadata meta = service.createOMEXMLMetadata(xml);

				Instrument exportInstr = new Instrument(root.getInstrument(series));
				omeXML.addInstrument(exportInstr);
			}catch (ServiceException | DependencyException e) {
				throw new FormatException(e);
			}
		}
		return omeXML;
	}
	
	private void convertToXML(MetadataStore store)  throws FormatException, IOException {
		
		if (store instanceof MetadataRetrieve) {
			try {
				ServiceFactory factory = new ServiceFactory();
				OMEXMLService service = factory.getInstance(OMEXMLService.class);
				String xml = service.getOMEXML(service.asRetrieve(store));
				OMEXMLMetadataRoot root = (OMEXMLMetadataRoot) store.getRoot();
				IMetadata meta = service.createOMEXMLMetadata(xml);

				Instrument exportInstr = new Instrument(root.getInstrument(series));
				Image exportImage = new Image(root.getImage(series));
				Pixels exportPixels = new Pixels(root.getImage(series).getPixels());
				exportPixels.setBinData(series, null);
				exportImage.setPixels(exportPixels);

				StructuredAnnotations exportStructAnnot=new StructuredAnnotations(root.getStructuredAnnotations());

				newRoot = (OMEXMLMetadataRoot) meta.getRoot();
				while (newRoot.sizeOfImageList() > 0) {
					newRoot.removeImage(newRoot.getImage(0));
				}
				while (newRoot.sizeOfPlateList() > 0) {
					newRoot.removePlate(newRoot.getPlate(0));
				}
				newRoot.addImage(exportImage);
				newRoot.addInstrument(exportInstr);
				newRoot.setStructuredAnnotations(exportStructAnnot);
				meta.setRoot(newRoot);
				//				 meta.setPixelsSizeX(new PositiveInteger(width), 0);
				//				 meta.setPixelsSizeY(new PositiveInteger(height), 0);


				//				 writer.setMetadataRetrieve((MetadataRetrieve) meta);


			}
			catch (ServiceException | DependencyException e) {
				throw new FormatException(e);
			}
		}
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

	private Image makeImage(int index) {
		
		CoreMetadata m = core.get(0,0);
		
		 // Create <Image/>
	    Image image = new Image();
	    image.setID("Image:" + index);
	    // Create <Pixels/>
	    Pixels pixels = new Pixels();
	    pixels.setID("Pixels:" + index);
	    pixels.setSizeX(new PositiveInteger(m.sizeX));
	    pixels.setSizeY(new PositiveInteger(m.sizeY));
	    pixels.setSizeZ(new PositiveInteger(m.sizeZ));
	    if(tiffDataFNames!=null && tiffDataFNames.size()>0) {
	    	pixels.setSizeC(new PositiveInteger(tiffDataFNames.size()));
	    	System.out.println("T: "+tiffDataFNames.get(0).size());
	    	if(tiffDataFNames.get(0)!=null && tiffDataFNames.get(0).size()>0) {
	    		pixels.setSizeT(new PositiveInteger(tiffDataFNames.get(0).size()));
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
	  
	    
	    if(tiffDataFNames!=null && !tiffDataFNames.isEmpty()) {
	    	// Create <TiffData/>
	    	for(int ch =0; ch<tiffDataFNames.size();ch++) {
	    		//		    // Create <Channel/> under <Pixels/>
	    		Channel channel = new Channel();
	    		channel.setID("Channel:" + ch);
	    		channel.setName("ch"+ch);
	    		pixels.addChannel(channel);
	    		
	    		List<String> fNameTimes=tiffDataFNames.get(ch);
	    		System.out.println("CH: "+ch+" - #T: "+fNameTimes.size());
	    		for(int t=0; t<fNameTimes.size();t++) {
	    			TiffData tiffData = new TiffData();
	    			tiffData.setFirstC(new NonNegativeInteger(ch));
	    			tiffData.setFirstT(new NonNegativeInteger(t));
	    			// Create <UUID/>
	    			UUID uuid = new UUID();
	    			uuid.setFileName(fNameTimes.get(t));

	    			tiffData.setUUID(uuid);
	    			pixels.addTiffData(tiffData);
	    		}
	    	}

	    }

	    image.setPixels(pixels);
	 
	    return image;
	}

	
	private void parseExperimentName() {
		Location baseFile = new Location(currentId).getAbsoluteFile();
		Location parent = baseFile.getParentFile();
		
		experimentDir=parent;
		experimentName=getExperimentName(baseFile.getName());
	}

	private void findCompanionFiles() {
		//TODO: that can be nicer implement
		File[] tiffs = new File(experimentDir.getAbsolutePath()).listFiles(new ImageFileFilter(experimentName));
		files = new String[tiffs.length];
		int k=0;
		for(File file : tiffs) {
			String path=file.getName();
			if(path.endsWith("msecAbs.tif")) {
				if(path.contains("_Settings")) {
					metaDataFile=path;
				}else {
					files[k]=path;
					System.out.println("Companion file: "+path+":: "+k);
					k++;
				}
			}
		}
	}

	
//	private Image makeImage() {
//	    // Create <Image/>
//	    Image image = new Image();
//	    image.setID(InOutCurrentTest.IMAGE_ID);
////	    ListAnnotation listAnnotation = new ListAnnotation();
////	    listAnnotation.setID(InOutCurrentTest.IMAGE_LIST_ANNOTATION_ID);
////	    listAnnotation.setNamespace(InOutCurrentTest.GENERAL_ANNOTATION_NAMESPACE);
////	    annotations.addListAnnotation(listAnnotation);
////	    BooleanAnnotation annotation = new BooleanAnnotation();
////	    annotation.setID(InOutCurrentTest.IMAGE_ANNOTATION_ID);
////	    annotation.setValue(InOutCurrentTest.IMAGE_ANNOTATION_VALUE);
////	    annotation.setNamespace(InOutCurrentTest.GENERAL_ANNOTATION_NAMESPACE);
////	    listAnnotation.linkAnnotation(annotation);
////	    image.linkAnnotation(listAnnotation);
////	    annotations.addBooleanAnnotation(annotation);
//	    // Create <Pixels/>
//	    Pixels pixels = new Pixels();
//	    pixels.setID(InOutCurrentTest.PIXELS_ID);
//	    pixels.setSizeX(new PositiveInteger(InOutCurrentTest.SIZE_X));
//	    pixels.setSizeY(new PositiveInteger(InOutCurrentTest.SIZE_Y));
//	    pixels.setSizeZ(new PositiveInteger(InOutCurrentTest.SIZE_Z));
//	    pixels.setSizeC(new PositiveInteger(InOutCurrentTest.SIZE_C));
//	    pixels.setSizeT(new PositiveInteger(InOutCurrentTest.SIZE_T));
//	    pixels.setDimensionOrder(InOutCurrentTest.DIMENSION_ORDER);
//	    pixels.setType(InOutCurrentTest.PIXEL_TYPE);
//	   
//	    // Create <TiffData/>
//	    TiffData tiffData = new TiffData();
//	    pixels.addTiffData(tiffData);
//	    // Create <Channel/> under <Pixels/>
//	    for (int i = 0; i < InOutCurrentTest.SIZE_C; i++) {
//	      Channel channel = new Channel();
//	      channel.setID("Channel:" + i);
//	      if (i == 0) {
//	        XMLAnnotation channelAnnotation = new XMLAnnotation();
//	        channelAnnotation.setID(InOutCurrentTest.CHANNEL_ANNOTATION_ID);
//	        channelAnnotation.setValue(InOutCurrentTest.CHANNEL_ANNOTATION_VALUE);
//	        channelAnnotation.setNamespace(InOutCurrentTest.GENERAL_ANNOTATION_NAMESPACE);
//	        channel.linkAnnotation(channelAnnotation);
//	        annotations.addXMLAnnotation(channelAnnotation);
//	      }
//	      pixels.addChannel(channel);
//	    }
//	    // Put <Pixels/> under <Image/>
//	    image.setPixels(pixels);
//	    return image;
//	  }


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
