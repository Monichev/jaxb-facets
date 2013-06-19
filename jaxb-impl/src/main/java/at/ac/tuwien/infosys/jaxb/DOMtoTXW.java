package at.ac.tuwien.infosys.jaxb;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Stack;

import javax.xml.namespace.QName;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import com.sun.xml.txw2.TypedXmlWriter;

public class DOMtoTXW implements ContentHandler {

    private Stack<TypedXmlWriter> elementWriters = new Stack<TypedXmlWriter>();

    public DOMtoTXW(TypedXmlWriter root) {
        elementWriters.push(root);
    }

    public void convert(String value) throws Exception {
        XMLReader xmlReader = XMLReaderFactory.createXMLReader();
        xmlReader.setContentHandler(this);
        xmlReader.parse(new InputSource(new ByteArrayInputStream(value.getBytes())));
    }

    private TypedXmlWriter getWriter() {
        return elementWriters.lastElement();
    }

    public void startElement(String uri, String localName, String qName, Attributes attrs) throws SAXException {
        TypedXmlWriter newElement = getWriter().
                _element(new QName(uri, localName), TypedXmlWriter.class);
        for(int i = 0; i < attrs.getLength(); i ++) {
            String n = attrs.getLocalName(i);
            String u = attrs.getURI(i);
            String v = attrs.getValue(i);
            if(u == null) {
                newElement._attribute(n, v);
            } else {
                newElement._attribute(u, n, v);
            }
        }
        elementWriters.push(newElement);
    }

    public void endElement(String arg0, String arg1, String arg2) throws SAXException {
        elementWriters.pop();
    }

    public void characters(char[] ch, int start, int length) throws SAXException {
        String data = new String(Arrays.copyOfRange(ch, start, start + length));
        getWriter()._pcdata(data);
    }


    public void startDocument() throws SAXException {
        /* swallow */
    }
    public void skippedEntity(String arg0) throws SAXException {
        /* swallow */
    }
    public void setDocumentLocator(Locator arg0) {
        /* swallow */
    }
    public void processingInstruction(String arg0, String arg1) throws SAXException {
        /* swallow */
    }
    public void ignorableWhitespace(char[] arg0, int arg1, int arg2) throws SAXException {
        /* swallow */
    }  
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        /* swallow */
    }
    public void endPrefixMapping(String arg0) throws SAXException {
        /* swallow */
    }
    public void endDocument() throws SAXException {
        /* swallow */
    }
}
