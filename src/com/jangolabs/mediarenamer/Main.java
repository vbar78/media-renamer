package com.jangolabs.mediarenamer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.sanselan.ImageReadException;
import org.apache.sanselan.Sanselan;
import org.apache.sanselan.common.IImageMetadata;
import org.apache.sanselan.formats.jpeg.JpegImageMetadata;
import org.apache.sanselan.formats.tiff.TiffField;
import org.apache.sanselan.formats.tiff.constants.ExifTagConstants;

public class Main {

    private static final Descriptor PICTURE_DESCRIPTOR = new Descriptor(
            "pictures",
            Arrays.asList(".jpg", ".jpeg"),
            Arrays.asList(
                    Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2})\\.(\\d{2})\\.(\\d{2})$"),
                    Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})$"),
                    Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-.+$"),
                    Pattern.compile("^IMG_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})(_\\d+|~\\d+)?$")
            ),
            true,
            Pattern.compile("^img-(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})(-\\d+)?"),
            "img-%s-%s-%s-%s-%s-%s");

    private static final Descriptor VIDEO_DESCRIPTOR = new Descriptor(
            "videos",
            Arrays.asList(".mov", ".mp4", ".avi"),
            Arrays.asList(
                    Pattern.compile("^VID_(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})(\\d{2})(_\\d+|~\\d+)?$")
            ),
            false,
            Pattern.compile("^video-(\\d{4})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})(-\\d+)?"),
            "video-%s-%s-%s-%s-%s-%s");

    private static final Pattern EXIF_DATE_PATTERN =
            Pattern.compile("^(\\d{4}):(\\d{2}):(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2}).?");

    private final boolean verify;
    private final Descriptor descriptor;
    private final File dir;

    public static void main(String[] args) {
        parseArgs(args).run();
    }

    private static Main parseArgs(String[] args) {
        boolean verify = false;
        Descriptor descriptor = null;
        File dir = null;

        for (int i = 0; i < args.length; i++) {
            final String arg = args[i];
            switch (arg) {

                case "-v":
                    verify = true;
                    break;

                case "-t":
                    i++;
                    if (i >= args.length) {
                        printUsageAndExit();
                    }
                    switch (args[i]) {
                        case "p":
                            descriptor = PICTURE_DESCRIPTOR;
                            break;
                        case "v":
                            descriptor = VIDEO_DESCRIPTOR;
                            break;
                        default:
                            printUsageAndExit();
                            break;
                    }
                    break;

                default:
                    dir = new File(arg);
                    if (i != args.length - 1) {
                        printUsageAndExit();
                    }
                    break;
            }
        }

        if (descriptor == null || dir == null) {
            printUsageAndExit();
        }

        return new Main(verify, descriptor, dir);
    }

    private static void printUsageAndExit() {
        System.out.println("Usage: java -jar <path-to-jar> -t p|v [-v] <path-to-dir>");
        System.exit(1);
    }

    private Main(boolean verify, Descriptor descriptor, File dir) {
        this.verify = verify;
        this.descriptor = descriptor;
        this.dir = dir;
    }

    private void run() {
        if (verify) {
            verify();
        } else {
            rename();
        }
    }

    private void verify() {
        System.out.println("Checking: " + descriptor.name + ", dir: " + dir);
        final File[] includedFiles = computeIncludedFiles();
        int count = 0;
        Set<File> fileSet = new HashSet<>(includedFiles.length);
        for (final File file : includedFiles) {
            try {
                final File chosenFile = chooseFile(file, (File f) -> fileSet.contains(f));
                if (fileSet.contains(chosenFile)) {
                    System.out.println("Chosen file already in set: " + chosenFile);
                    continue;
                }
                if (chosenFile.equals(file)) {
                    System.out.println("Skipping already in format: " + chosenFile);
                    continue;
                }
                fileSet.add(chosenFile);
                System.out.println(file + " -> " + chosenFile);
                count++;
            } catch (ImageReadException|IOException e) {
                System.out.println("Error reading: " + file + ", " + e);
            }
        }
        System.out.println("Checked total: " + count);
    }

    private void rename() {
        System.out.println("Renaming: " + descriptor.name + ", dir: " + dir);
        final File[] includedFiles = computeIncludedFiles();
        int count = 0;
        for (final File file : includedFiles) {
            try {
                final File chosenFile = chooseFile(file, (File f) -> f.exists());
                if (chosenFile.equals(file)) {
                    System.out.println("Skipping already in format: " + chosenFile);
                    continue;
                }
                if (chosenFile.exists()) {
                    System.out.println("Chosen file already exists: " + chosenFile);
                    continue;
                }
                System.out.println(file + " -> " + chosenFile);
                final boolean result = file.renameTo(chosenFile);
                if (!result) {
                    System.out.println("Failed to rename: " + file + ", to: " + chosenFile);
                    continue;
                }
                count++;
            } catch (ImageReadException|IOException e) {
                System.out.println("Error processing: " + file + ", " + e);
            }
        }
        System.out.println("Renamed total: " + count);
    }

    private File chooseFile(File file, Function<File, Boolean> collisionTest) throws IOException, ImageReadException {
        final String choosenFilename = chooseFilename(file);
        for (int i = 0; ; i++) {
            final String filename = choosenFilename;
            final String name;
            final String ext;
            final int extPos = filename.lastIndexOf('.');
            if (extPos != -1) {
                name = filename.substring(0, extPos);
                ext = filename.substring(extPos);
            } else {
                name = filename;
                ext = "";
            }
            final String tryFileName = name + (i != 0 ? ("-" + i) : "") + ext;
            final File tryFile = new File(file.getParent(), tryFileName);
            if (tryFile.equals(file)) {
                return file;
            }
            if (!collisionTest.apply(tryFile)) {
                if (i != 0) {
                    System.out.println("De-duped name: " + file + " -> " + tryFileName);
                }
                return tryFile;
            }
        }
    }

    private String chooseFilename(File file) throws ImageReadException, IOException {
        final String filename = file.getName();
        final String filenameNoExt;
        final String ext;
        final int extPos = filename.lastIndexOf('.');
        if (extPos != -1) {
            filenameNoExt = filename.substring(0, extPos);
            ext = filename.substring(extPos);
        } else {
            filenameNoExt = filename;
            ext = "";
        }

        if (descriptor.targetNamePattern.matcher(filenameNoExt).matches()) {
            return file.getName();
        }

        for (final Pattern pattern : descriptor.namePatterns) {
            final Matcher m = pattern.matcher(filenameNoExt);
            if (m.matches()) {
                final String yyyy = m.group(1);
                final String mm = m.group(2);
                final String dd = m.group(3);
                final String hh = m.group(4);
                final String ii = m.group(5);
                final String ss = m.group(6);
                final String result = filenameFromComponents(yyyy, mm, dd, hh, ii, ss, ext);
                System.out.print("PATT> ");
                return result;
            }
        }

        if (descriptor.exif) {
            final IImageMetadata metadata = Sanselan.getMetadata(file);
            final JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
            if (jpegMetadata != null) {
                final TiffField field = jpegMetadata.findEXIFValue(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
                if (field != null) {
                    final String dateOriginal = field.getStringValue();
                    final Matcher m = EXIF_DATE_PATTERN.matcher(dateOriginal);
                    if (m.matches()) {
                        final String yyyy = m.group(1);
                        final String mm = m.group(2);
                        final String dd = m.group(3);
                        final String hh = m.group(4);
                        final String ii = m.group(5);
                        final String ss = m.group(6);
                        final String result = filenameFromComponents(yyyy, mm, dd, hh, ii, ss, ext);
                        System.out.print("EXIF> ");
                        return result;
                    } else {
                        System.out.println("Bad DATE_TIME_ORIGINAL: [" + dateOriginal + "]");
                    }
                }
            }
        }

        final Date creationDate = Date.from(
                Files.readAttributes(Paths.get(file.getAbsolutePath()), BasicFileAttributes.class)
                .creationTime().toInstant());
        System.out.print("!!!!> ");
        return filenameFromDate(creationDate, ext);
    }

    private String filenameFromComponents(String yyyy, String mm, String dd, String hh, String ii, String ss,
            String ext) {
        return String.format(descriptor.targetNameFormat, yyyy, mm, dd, hh, ii, ss) + ext;
    }

    private static final SimpleDateFormat DATE_FORMAT_YYYY = new SimpleDateFormat("yyyy");
    private static final SimpleDateFormat DATE_FORMAT_MM = new SimpleDateFormat("MM");
    private static final SimpleDateFormat DATE_FORMAT_DD = new SimpleDateFormat("dd");
    private static final SimpleDateFormat DATE_FORMAT_HH = new SimpleDateFormat("HH");
    private static final SimpleDateFormat DATE_FORMAT_II = new SimpleDateFormat("mm");
    private static final SimpleDateFormat DATE_FORMAT_SS = new SimpleDateFormat("ss");

    private String filenameFromDate(Date date, String ext) {
        final String yyyy = DATE_FORMAT_YYYY.format(date);
        final String mm = DATE_FORMAT_MM.format(date);
        final String dd = DATE_FORMAT_DD.format(date);
        final String hh = DATE_FORMAT_HH.format(date);
        final String ii = DATE_FORMAT_II.format(date);
        final String ss = DATE_FORMAT_SS.format(date);
        return filenameFromComponents(yyyy, mm, dd, hh, ii, ss, ext);
    }

    private File[] computeIncludedFiles() {
        if (!dir.exists()) {
            return new File[0];
        }
        File[] result = dir.listFiles((File tmp, String name) -> {
            return descriptor.extensions.stream().anyMatch((ext) -> (name.toLowerCase().endsWith(ext)));
        });
        Arrays.sort(result);
        System.out.println("Included files: " + result.length);
        Set<File> excludedFiles = new HashSet<>(Arrays.asList(dir.listFiles()));
        excludedFiles.removeAll(new HashSet<>(Arrays.asList(result)));
        System.out.println("Excluded files: " + excludedFiles);
        return result;
    }

    private static final class Descriptor {
        final String name;
        final List<String> extensions;
        final List<Pattern> namePatterns;
        final boolean exif;
        final Pattern targetNamePattern;
        final String targetNameFormat;

        public Descriptor(String name, List<String> extenstions, List<Pattern> namePatterns, boolean exif,
                Pattern targetNamePattern, String targetNameFormat) {
            this.name = name;
            this.extensions = extenstions;
            this.namePatterns = namePatterns;
            this.exif = exif;
            this.targetNamePattern = targetNamePattern;
            this.targetNameFormat = targetNameFormat;
        }
    }
}
