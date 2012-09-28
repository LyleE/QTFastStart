package com.entwistle.qtfaststart;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class QTFastStart {
    
    private final static boolean DEBUG = false;
    private final static int CHUNK_SIZE = 8192;
    
    private static class Atom {
        public String type;
        public long size;
        public long start;
        
        public Atom(String t, long s) {
            type = t;
            size = s;
        }
        
        @Override
        public String toString() {
            return "["+type+",start="+start+",size="+size+"]";
        }
    }
    
    private static int readInt(InputStream in) throws IOException {
        byte[] data = new byte[4];
        in.read(data);
        
        int n = 0;
        for(byte b: data)
            n = (n<<8) + (b & 0xFF);
        return n;
    }
    
    private static long readLong(InputStream in) throws IOException {
        byte[] data = new byte[8];
        in.read(data);
        
        long n = 0;
        for(byte b: data)
            n = (n<<8) + (b & 0xFF);
        return n;
    }
    
    private static String readType(InputStream in) throws IOException {
        byte[] data = new byte[4];
        in.read(data);
        return new String(data);
    }
    
    private static Atom readAtom(InputStream in) throws IOException {
        int size = readInt(in);
        String type = readType(in);
        return new Atom(type, size);
    }
    
    private static byte[] toByteArray(int n) {
        byte[] b = new byte[4];
        for(int i = 0;i<4;i++)
            b[3-i] = (byte)(n>>>8*i);
        return b;
    }
    
    private static byte[] toByteArray(long n) {
        byte[] b = new byte[8];
        for(int i = 0;i<8;i++)
            b[7-i] = (byte)(n>>>8*i);
        return b;
    }
    
    private static ArrayList<Atom> getIndex(FileInputStream in) throws IOException, FastStartException
    {
        if(DEBUG) System.out.println("Getting index of top level atoms...");
        
        ArrayList<Atom> index = new ArrayList<Atom>();
        boolean seenMoov = false;
        boolean seenMdat = false;
        
        while(in.available()>0) {
            try {
                Atom atom = readAtom(in);
                int skippedBytes = 8;
                if(atom.size == 1) {
                    atom.size = readLong(in);
                    skippedBytes = 16;
                }
                
                atom.start = in.getChannel().position() - skippedBytes;
                index.add(atom);
                
                if(DEBUG) System.out.println(atom);
                
                if(atom.type.equalsIgnoreCase("moov")) seenMoov = true;
                if(atom.type.equalsIgnoreCase("mdat")) seenMdat = true;
                
                if(atom.size == 0) break;
                
                in.skip(atom.size - skippedBytes);
                
            } catch(IOException ex) {
                if(DEBUG) ex.printStackTrace();
                break;
            }
        }
        
        if(!seenMoov) throw new FastStartException("No moov atom type found!");
        if(!seenMdat) throw new FastStartException("No mdat atom type found!");
        
        return index;
    }
    
    private static Atom skipToNextTable(InputStream in) throws IOException, FastStartException
    {
        while(in.available()>0) {
            Atom atom = readAtom(in);
            if(isTableType(atom.type))
                return atom;
            else if(isKnownAncestorType(atom.type))
                continue;
            else
                in.skip(atom.size - 8);
        }
        
        return null;
    }
    
    private static boolean isKnownAncestorType(String type) {
        return
            type.equalsIgnoreCase("trak") ||
            type.equalsIgnoreCase("mdia") ||
            type.equalsIgnoreCase("minf") ||
            type.equalsIgnoreCase("stbl");
    }
    
    private static boolean isTableType(String type) {
        return
        type.equalsIgnoreCase("stco") ||
        type.equalsIgnoreCase("co64");
    }
    
    public static File process(File inputFile, File outputFile) throws FastStartException {
        FileInputStream in;
        try {
            in = new FileInputStream(inputFile);
        } catch (IOException ex) {
            if(DEBUG) ex.printStackTrace();
            throw new FastStartException("IO Exception when initialising the input file stream.");
        }
        
        ArrayList<Atom> index;
        
        try {
            index = getIndex(in);
        } catch (IOException ex) {
            if(DEBUG) ex.printStackTrace();
            throw new FastStartException("IO Exception during indexing.");
        }
        
        Atom moov = null;
        long mdatStart = 999999l;
        long freeSize = 0l;
        
        //Check that moov is after mdat (they are both known to exist)
        for (Atom atom: index) {
            if(atom.type.equalsIgnoreCase("moov")) {
                moov = atom;
            } else if(atom.type.equalsIgnoreCase("mdat")) {
                mdatStart = atom.start;
            } else if(atom.type.equalsIgnoreCase("free") && atom.start < mdatStart) {
                //This free atom is before the mdat
                freeSize += atom.size;
                if(DEBUG)
                    System.out.println("Removing free atom at "+atom.start+" ("+atom.size+" bytes)");
            }
        }
        
        int offset = (int)(moov.size - freeSize);
        
        
        if(moov.start < mdatStart) {
            offset -= moov.size;
            if(freeSize == 0) {
                //good to go already!
                if(DEBUG)
                        System.out.println("File already suitable.");
                return inputFile;
            }
        }
        
        byte[] moovContents = new byte[(int)moov.size];
        try {
            in.getChannel().position(moov.start);
            in.read(moovContents);
        } catch (IOException ex) {
            if(DEBUG) ex.printStackTrace();
            throw new FastStartException("IO Exception reading moov contents.");
        }
        
        ByteArrayInputStreamPosition moovIn = new ByteArrayInputStreamPosition(moovContents);
        ByteArrayOutputStream moovOut = new ByteArrayOutputStream(moovContents.length);
        
        //Skip type and size
        moovIn.skip(8);
        
        try {
            Atom atom;
            while((atom=skipToNextTable(moovIn)) != null) {
                moovIn.skip(4); //skip version and flags
                int entryCount = readInt(moovIn);
                if(DEBUG) System.out.println("Patching "+atom.type+" with "+entryCount+" entries.");
                
                int entriesStart = moovIn.position();
                //write up to start of the entries
                moovOut.write(moovContents, moovOut.size(), entriesStart-moovOut.size());
                
                if(atom.type.equalsIgnoreCase("stco")) { //32 bit
                    byte[] entry;
                    for(int i = 0;i<entryCount;i++) {
                        entry = toByteArray(readInt(moovIn) + offset);
                        moovOut.write(entry);
                    }
                } else { //64 bit
                    byte[] entry;
                    for(int i = 0;i<entryCount;i++) {
                        entry = toByteArray(readLong(moovIn) + offset);
                        moovOut.write(entry);
                    }
                }
            }
            
            if(moovOut.size()<moovContents.length) //write the rest
                moovOut.write(moovContents, moovOut.size(), moovContents.length-moovOut.size());
            
        } catch(IOException ex) {
            if(DEBUG) ex.printStackTrace();
            throw new FastStartException("IO Exception while patching moov.");
        }
        
        FileOutputStream out;
        
        try {
            if(outputFile.exists()) {
                outputFile.setWritable(true, true);
                outputFile.delete();
            }
            outputFile.createNewFile();
            //outputFile.deleteOnExit();
            
            out = new FileOutputStream(outputFile);
        } catch (IOException ex) {
            if(DEBUG) ex.printStackTrace();
            throw new FastStartException("IO Exception during output file setup.");
        }
        
        if(DEBUG) System.out.println("Writing output file:");
        
        //write ftype
        for(Atom atom: index) {
            if(atom.type.equalsIgnoreCase("ftyp")) {
                if(DEBUG) System.out.println("Writing ftyp...");
                try {
                    in.getChannel().position(atom.start);
                    byte[] data = new byte[(int)atom.size];
                    in.read(data);
                    out.write(data);
                } catch (IOException ex) {
                    if(DEBUG) ex.printStackTrace();
                    throw new FastStartException("IO Exception during writing ftype.");
                }
            }
        }
        
        //write moov
        try {
            if(DEBUG) System.out.println("Writing moov...");
            //if(DEBUG) System.out.println("Modified moov contents:\n"+moovOut.toString());
            
            moovOut.writeTo(out);
            
            moovIn.close();
            moovOut.close();
        } catch (IOException ex) {
            if(DEBUG) ex.printStackTrace();
            throw new FastStartException("IO Exception during writing moov.");
        }
        
        //write everything else!
        for(Atom atom: index) {
            if( atom.type.equalsIgnoreCase("ftyp") ||
                atom.type.equalsIgnoreCase("moov") ||
                atom.type.equalsIgnoreCase("free"))
            {
                continue;
            }
            
            if(DEBUG) System.out.println("Writing "+atom.type+"...");
            
            try {
                in.getChannel().position(atom.start);
                
                byte[] chunk = new byte[CHUNK_SIZE];
                for(int i = 0; i < (atom.size/CHUNK_SIZE);i++) {
                    in.read(chunk);
                    out.write(chunk);
                }
                
                int remainder = (int)(atom.size%CHUNK_SIZE);
                if(remainder>0) {
                    in.read(chunk, 0, remainder);
                    out.write(chunk, 0, remainder);
                }
            } catch (IOException ex) {
                if(DEBUG) ex.printStackTrace();
                throw new FastStartException("IO Exception during output writing.");
            }
        }
        
        if(DEBUG) System.out.println("Write complete!");
        
        //cleanup
        try {
            in.close();
            out.flush();
            out.close();
        } catch(IOException e) { /* Intentionally empty */ }
        
        return outputFile;
    }
    
    /* For testing:
    public static void main(String[] args) {
        String filePath = "File path here";
        
        File input = new File(filePath);
        File output = new File(input.getParent(),"processed_"+input.getName());
        
        try {
            process(input, output);
        } catch(FastStartException ex) {
            System.out.println(ex.getMessage());
        }
    }*/
    
}
