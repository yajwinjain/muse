package edu.stanford.muse.util;

import edu.stanford.muse.webapp.JSPHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Created by vihari on 24/02/16.
 * Similar in spirit to opennlp.tools.util
 * used to represent the chunks recognized by a NER model, @see edu.stanford.muse.ner.model.NERModel interface
 */
public class Span {
    public int start, end;
    public String text;
    public short type = -1;
    public float typeScore = 0f;
    //link to the possible expansion of this entity
    public String link;
    public float linkConf;
    public static Log log = LogFactory.getLog(Span.class);


    /**
     * @param start - The start offset of chunk in the content
     * @param end - end offset*/
    public Span(String chunk, int start, int end){
        this.text = chunk;
        this.start = start;
        this.end = end;
    }

    public void setType(short type, float score){this.type = type; this.typeScore = score;}

    public short getType(){return type;}

    public void setLink(String expansion, float confidence){this.link = expansion; this.linkConf = confidence;}

    @Override
    public String toString() {
        return "[" + this.start + ".." + this.end + ")" + " " + this.type + " " + this.typeScore + (this.link!=null?(link+"["+linkConf+"]"):"");
    }

    /**Prints in parse friendly manner*/
    public String parsablePrint(){
        return this.text+";"+this.start+";"+this.end+";"+this.type+";"+this.typeScore+";"+(this.link!=null?(this.link+";"+this.linkConf):"");
    }

    /**Given a text printed by parsablePrint, parses it and returns handle to the initialized object*/
    public static Span parse(String text){
        if(text == null) {
            JSPHelper.log.warn("Found null content while parsing entity spans!!!");
            return null;
        }
        String[] fields = text.split(";");
        if(fields.length!=7 && fields.length!=5) {
            log.warn("Unexpected number of fields in content: "+text);
            return null;
        }
        Span chunk = new Span(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2]));
        chunk.setType(Short.parseShort(fields[3]), Float.parseFloat(fields[4]));
        if(fields.length>5)
            chunk.setLink(fields[5], Float.parseFloat(fields[6]));
        return chunk;
    }
}
