/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.zee.amazondataset;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.apache.hadoop.util.LineReader;
import java.io.*;
import org.apache.hadoop.io.Text;

/**
 *
 * @author zeenux
 */
public class CustomRecordReaderExtendingLineReader extends LineReader {

    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;
    private int bufferSize = DEFAULT_BUFFER_SIZE;
    private InputStream in;
    private byte[] buffer;
    // the number of bytes of real data in the buffer
    private int bufferLength = 0;
    // the current position in the buffer
    private int bufferPosn = 0;

    private static final byte CR = '\r';
    private static final byte LF = '\n';

    // The line delimiter
    private final byte[] recordDelimiterBytes = null;

    public CustomRecordReaderExtendingLineReader(FSDataInputStream fin, Configuration job) throws IOException {
            
        super(fin, job);
       // System.out.println("CustomRecordReaderExtendingLineReader called");
    }

    public void close() throws IOException {
        //System.out.println("Close Called");
        if(in!=null)
            in.close();
    }

    public int readLine(Text str, int maxLineLength,  int maxBytesToConsume) throws IOException {
        System.out.println("readLine called");
        if (this.recordDelimiterBytes != null) {
            return readCustomLine(str, maxLineLength, maxBytesToConsume);
        } else {
            return readDefaultLine(str, maxLineLength, maxBytesToConsume);
        }
    }
    
    
    private int readCustomLine(Text str, int maxLineLength, int maxBytesToConsume)
      throws IOException {
   /* We're reading data from inputStream, but the head of the stream may be
    *  already captured in the previous buffer, so we have several cases:
    * 
    * 1. The buffer tail does not contain any character sequence which
    *    matches with the head of delimiter. We count it as a 
    *    ambiguous byte count = 0
    *    
    * 2. The buffer tail contains a X number of characters,
    *    that forms a sequence, which matches with the
    *    head of delimiter. We count ambiguous byte count = X
    *    
    *    // ***  eg: A segment of input file is as follows
    *    
    *    " record 1792: I found this bug very interesting and
    *     I have completely read about it. record 1793: This bug
    *     can be solved easily record 1794: This ." 
    *    
    *    delimiter = "record";
    *        
    *    supposing:- String at the end of buffer =
    *    "I found this bug very interesting and I have completely re"
    *    There for next buffer = "ad about it. record 179       ...."           
    *     
    *     The matching characters in the input
    *     buffer tail and delimiter head = "re" 
    *     Therefore, ambiguous byte count = 2 ****   //
    *     
    *     2.1 If the following bytes are the remaining characters of
    *         the delimiter, then we have to capture only up to the starting 
    *         position of delimiter. That means, we need not include the 
    *         ambiguous characters in str.
    *     
    *     2.2 If the following bytes are not the remaining characters of
    *         the delimiter ( as mentioned in the example ), 
    *         then we have to include the ambiguous characters in str. 
    */
    str.clear();
    int txtLength = 0; // tracks str.getLength(), as an optimization
    long bytesConsumed = 0;
    int delPosn = 0;
    int ambiguousByteCount=0; // To capture the ambiguous characters count
    do {
      int startPosn = bufferPosn; // Start from previous end position
      if (bufferPosn >= bufferLength) {
        startPosn = bufferPosn = 0;
        bufferLength = fillBuffer(in, buffer, ambiguousByteCount > 0);
        if (bufferLength <= 0) {
          if (ambiguousByteCount > 0) {
            str.append(recordDelimiterBytes, 0, ambiguousByteCount);
            bytesConsumed += ambiguousByteCount;
          }
          break; // EOF
        }
      }
      for (; bufferPosn < bufferLength; ++bufferPosn) {
        if (buffer[bufferPosn] == recordDelimiterBytes[delPosn]) {
          delPosn++;
          if (delPosn >= recordDelimiterBytes.length) {
            bufferPosn++;
            break;
          }
        } else if (delPosn != 0) {
          bufferPosn -= delPosn;
          if(bufferPosn < -1) {
            bufferPosn = -1;
          }
          delPosn = 0;
        }
      }
      int readLength = bufferPosn - startPosn;
      bytesConsumed += readLength;
      int appendLength = readLength - delPosn;
      if (appendLength > maxLineLength - txtLength) {
        appendLength = maxLineLength - txtLength;
      }
      bytesConsumed += ambiguousByteCount;
      if (appendLength >= 0 && ambiguousByteCount > 0) {
        //appending the ambiguous characters (refer case 2.2)
        str.append(recordDelimiterBytes, 0, ambiguousByteCount);
        ambiguousByteCount = 0;
        // since it is now certain that the split did not split a delimiter we
        // should not read the next record: clear the flag otherwise duplicate
        // records could be generated
        unsetNeedAdditionalRecordAfterSplit();
      }
      if (appendLength > 0) {
        str.append(buffer, startPosn, appendLength);
        txtLength += appendLength;
      }
      if (bufferPosn >= bufferLength) {
        if (delPosn > 0 && delPosn < recordDelimiterBytes.length) {
          ambiguousByteCount = delPosn;
          bytesConsumed -= ambiguousByteCount; //to be consumed in next
        }
      }
    } while (delPosn < recordDelimiterBytes.length 
        && bytesConsumed < maxBytesToConsume);
    if (bytesConsumed > Integer.MAX_VALUE) {
      throw new IOException("Too many bytes before delimiter: " + bytesConsumed);
    }
    return (int) bytesConsumed; 
  }
private int readDefaultLine(Text str, int maxLineLength, int maxBytesToConsume)
  throws IOException {
    /* We're reading data from in, but the head of the stream may be
     * already buffered in buffer, so we have several cases:
     * 1. No newline characters are in the buffer, so we need to copy
     *    everything and read another buffer from the stream.
     * 2. An unambiguously terminated line is in buffer, so we just
     *    copy to str.
     * 3. Ambiguously terminated line is in buffer, i.e. buffer ends
     *    in CR.  In this case we copy everything up to CR to str, but
     *    we also need to see what follows CR: if it's LF, then we
     *    need consume LF as well, so next call to readLine will read
     *    from after that.
     * We use a flag prevCharCR to signal if previous character was CR
     * and, if it happens to be at the end of the buffer, delay
     * consuming it until we have a chance to look at the char that
     * follows.
     */
    str.clear();
    int txtLength = 0; //tracks str.getLength(), as an optimization
    int newlineLength = 0; //length of terminating newline
    boolean prevCharCR = false; //true of prev char was CR
    long bytesConsumed = 0;
    do {
      int startPosn = bufferPosn; //starting from where we left off the last time
      if (bufferPosn >= bufferLength) {
        startPosn = bufferPosn = 0;
        if (prevCharCR) {
          ++bytesConsumed; //account for CR from previous read
        }
        bufferLength = fillBuffer(in, buffer, prevCharCR);
        if (bufferLength <= 0) {
          break; // EOF
        }
      }
      for (; bufferPosn < bufferLength; ++bufferPosn) { //search for newline
        if (buffer[bufferPosn] == LF) {
          newlineLength = (prevCharCR) ? 2 : 1;
          ++bufferPosn; // at next invocation proceed from following byte
          break;
        }
        if (prevCharCR) { //CR + notLF, we are at notLF
          newlineLength = 1;
          break;
        }
        prevCharCR = (buffer[bufferPosn] == CR);
      }
      int readLength = bufferPosn - startPosn;
      if (prevCharCR && newlineLength == 0) {
        --readLength; //CR at the end of the buffer
      }
      bytesConsumed += readLength;
      int appendLength = readLength - newlineLength;
      if (appendLength > maxLineLength - txtLength) {
        appendLength = maxLineLength - txtLength;
      }
      if (appendLength > 0) {
        str.append(buffer, startPosn, appendLength);
        txtLength += appendLength;
      }
    } while (newlineLength == 0 && bytesConsumed < maxBytesToConsume);

    if (bytesConsumed > Integer.MAX_VALUE) {
      throw new IOException("Too many bytes before newline: " + bytesConsumed);
    }
    return (int)bytesConsumed;
  }

}
