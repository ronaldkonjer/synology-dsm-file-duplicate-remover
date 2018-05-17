package syno.fileduplicateremover;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class SynoFileDuplicateRemover {

    public static void main(String[] args) {

        String synoDuplicateListCsvFile = "";
        boolean dryRun = false;
        Options options = new Options();

        options.addOption(Option.builder("csv_file")
                .required(true)
                .hasArg(true).argName("csv_file")
                .desc("required, the csv file containing the duplicate file list generated by the Synology NAS (DSM).").build());

        options.addOption(Option.builder("dry_run")
                .required(false)
                .hasArg(false).argName("dry_run")
                .desc("optional, run in dry_run mode. No deletion.").build());
        try {
            CommandLine commandLine = new DefaultParser().parse(options, args);
            synoDuplicateListCsvFile = commandLine.getOptionValue("csv_file");
            if (commandLine.getOptions().length == 2) {
                if ("dry_run".equals(commandLine.getOptions()[0].getArgName()) || "dry_run".equals(commandLine.getOptions()[1].getArgName())) {
                    dryRun = true;
                }
            }
            deleteDuplicateFiles(synoDuplicateListCsvFile, dryRun);

        } catch (ParseException e) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.setOptionComparator(null); // Keep insertion order of options
            formatter.printHelp("SynoFileDuplicateRemover", "Remove the duplicate files from the Synology NAS (DSM)", options, null);
            System.exit(1);
            return;			
        }

    }

    public static void deleteDuplicateFiles(String synoDuplicateListCsvFile, boolean dryRun) {
        int removedFilesCounter = 0;		
        int duplicateIndex = 0;
        int prevDuplicateIndex = 0;
        long fileSizeUsageFreedInBytes = 0L; 
        long start = System.currentTimeMillis();
        BufferedReader fileReader = null;

        List<DuplicateFile> duplicateFilesList = new ArrayList<DuplicateFile> ();
        Map<Integer, List<DuplicateFile>> duplicateFilesToRemove = new HashMap<Integer, List<DuplicateFile>> ();

        FileCursor fc = new SynoFileDuplicateRemover().new FileCursor();
        fc.lineIndex = 0;

        try {			

            fileReader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(synoDuplicateListCsvFile).getAbsolutePath()), "ISO-8859-1"));
            if (dryRun) System.out.println("Running in dry run mode no deletion.. ");
            System.out.println("Visiting the content of the csv file.. " + new File(synoDuplicateListCsvFile).getAbsolutePath());
            System.out.println("Grouping the files together by duplicate id\n");

            while((fc.currentLine = fileReader.readLine()) != null) {                         

                if (fc.lineIndex > 0 && fc.currentLine.replace("\0", "").length() > 0) {

                    DuplicateFile duplicateFile = fc.build();
                    duplicateIndex = duplicateFile.duplicateId;		        	
                    System.out.print("Line" +fc.lineIndex+"    Managing index: "+ duplicateIndex);

                    if (fc.lineIndex == 1) {
                        prevDuplicateIndex = duplicateIndex;		        		
                        System.out.println("    Adding: " + duplicateFile.fileLocation);
                        duplicateFilesList.add(duplicateFile);		        		

                    } else {
                        if (prevDuplicateIndex == duplicateIndex) {
                            duplicateFilesList.add(duplicateFile);
                            System.out.println("    Adding: " + duplicateFile.fileLocation);
                        } 
                        else {
                            List<DuplicateFile> prevDuplicateFilesList = new ArrayList<> ();
                            prevDuplicateFilesList.addAll(duplicateFilesList);
                            duplicateFilesToRemove.put(prevDuplicateIndex, prevDuplicateFilesList);

                            duplicateFilesList = new ArrayList<>();
                            duplicateFilesList.add(duplicateFile);
                            System.out.println("    Adding: " + duplicateFile.fileLocation);
                            prevDuplicateIndex = duplicateIndex;		        			
                        }
                    } 		        	
                } 
                if (fc.currentLine.replace("\0", "").length() > 0) {
                    fc.lineIndex ++;
                    fc.flush();
                }

            }
            fileReader.close();

            System.out.println(duplicateFilesToRemove.size());
            System.out.println("\nStarting the deletion\n");

            for (Entry<Integer, List<DuplicateFile>> entry: duplicateFilesToRemove.entrySet()) {

                List<DuplicateFile> fileList = entry.getValue();
                System.out.println("Visiting duplicate id " + entry.getKey() + " - list of: " + fileList.size() + " items.");
                for (int i = fileList.size() -1; i > 0; i--) {
                    String fileToRemove = fileList.get(i).fileLocation;
                    Path pathFileToRemove = Paths.get(fileToRemove.replaceAll("\"|\0|\\s", ""));
                    System.out.println("    Duplicate id: " + entry.getKey()+" removing duplicate file: " + pathFileToRemove.toString() );
                    if (!dryRun) {
                        try {
                            Files.delete(pathFileToRemove);
                        } catch (NoSuchFileException | DirectoryNotEmptyException exc) {
                            exc.printStackTrace();
                        }
                    }
                    fileSizeUsageFreedInBytes += Long.parseLong(fileList.get(i).fileSizeInByte);
                    removedFilesCounter ++;
                }
            }
        } catch (IOException exc) {
            exc.printStackTrace();

        } finally {
            if (fileReader != null) try { fileReader.close(); } catch (IOException logOrIgnore) {}
        }
        System.out.println("\nSummary\n");
        System.out.println("Visited files: " + fc.lineIndex--);
        System.out.println("Group count (duplicate id): " + duplicateIndex);
        System.out.println("Removed files: " + removedFilesCounter + " freed up space: " + (fileSizeUsageFreedInBytes/(1024*1024*1024)) + " G");
        System.out.println("Files remaining: " + ((fc.lineIndex-1) - removedFilesCounter));
        long end = System.currentTimeMillis() - start;
        System.out.println("Time taken: " + (end/1000) + " s");
    }

    public class DuplicateFile {
        int duplicateId;
        String fileLocation;
        String fileSizeInByte;

    }

    public class FileCursor {
        public int lineIndex;
        public String currentLine;
        public String[] lineTokens;		

        public DuplicateFile build() {
            String[] fileLineTokens = currentLine.split("\t"); // wrong format exit		        	
            DuplicateFile duplicateFile = new SynoFileDuplicateRemover().new DuplicateFile();
            duplicateFile.duplicateId = Integer.parseInt(fileLineTokens[0].replaceAll("\"|\0", ""));
            duplicateFile.fileLocation = fileLineTokens[2].replace("\"", "").replace("\0", "");
            duplicateFile.fileSizeInByte = fileLineTokens[3].replace("\"", "").replace("\0", "");
            return duplicateFile;
        }

        public void flush() {
            currentLine = "";
        }
    }

}
