package at.ac.tuwien.infosys.jaxb;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.bind.annotation.AppInfo;
import javax.xml.bind.annotation.Documentation;
import javax.xml.bind.annotation.Facets;
import javax.xml.bind.annotation.Facets.FacetDefinition;
import javax.xml.bind.annotation.Facets.WhiteSpace;
import javax.xml.bind.annotation.MaxOccurs;
import javax.xml.bind.annotation.MinOccurs;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import com.sun.xml.bind.v2.model.core.ArrayInfo;
import com.sun.xml.bind.v2.model.core.AttributePropertyInfo;
import com.sun.xml.bind.v2.model.core.ClassInfo;
import com.sun.xml.bind.v2.model.core.EnumConstant;
import com.sun.xml.bind.v2.model.core.EnumLeafInfo;
import com.sun.xml.bind.v2.model.core.PropertyInfo;
import com.sun.xml.bind.v2.model.core.TypeRef;
import com.sun.xml.bind.v2.model.core.ValuePropertyInfo;
import com.sun.xml.bind.v2.model.runtime.RuntimeElementPropertyInfo;
import com.sun.xml.bind.v2.schemagen.xmlschema.LocalAttribute;
import com.sun.xml.bind.v2.schemagen.xmlschema.LocalElement;
import com.sun.xml.bind.v2.schemagen.xmlschema.SimpleRestriction;
import com.sun.xml.txw2.TypedXmlWriter;
import com.sun.xml.txw2.output.ResultFactory;
import com.sun.xml.txw2.output.TXWResult;
import com.sun.xml.txw2.output.TXWSerializer;
import com.sun.xml.txw2.output.XmlSerializer;

/**
 * @author Waldemar Hummer (hummer@infosys.tuwien.ac.at)
 * @version 0.2 added support for Facet restrictions for XML attributes
 * @version 0.3 fixed classloading/proxying issue for JBoss, related to:
 *          http://lists.jboss.org/pipermail/forge-issues/2011-October/000351.html
 */
@SuppressWarnings("all")
public class XmlSchemaEnhancer {
    public static final String NS_XSD = "http://www.w3.org/2001/XMLSchema";
    public static final String NS_XML = "http://www.w3.org/XML/1998/namespace";

    private static final DocumentBuilderFactory XML_FACTORY = DocumentBuilderFactory.newInstance();

    public static final Logger logger = Logger
            .getLogger(XmlSchemaEnhancer.class.getName());

    public static <T, C> boolean hasExtendedAnnotations(TypeRef<T, C> t) {
        return hasFacets(t) || hasXsdAnnotations(t);
    }

    public static <T, C> boolean hasExtendedAnnotations(
            AttributePropertyInfo<T, C> info) {
        return hasFacets(info) || hasXsdAnnotations(info);
    }

    public static <T, C> void addFacets(ValuePropertyInfo<T, C> vp,
            SimpleRestriction restriction) {
        if (!hasFacets(vp))
            return;

        Facets facetsAnno = getFacetsAnnotation(vp);
        addFacets(facetsAnno, restriction);
    }

    public static <T, C> void addFacets(TypeRef<T, C> t, LocalElement e) {
        if (!hasFacets(t))
            return;

        Facets facetsAnno = getFacetsAnnotation(t);
        TypedXmlWriter restriction = getRestriction(t, e, null);
        addFacets(facetsAnno, restriction);
    }

    public static <T, C> void addFacets(AttributePropertyInfo<T, C> info,
            LocalAttribute attr) {
        if (!hasFacets(info))
            return;

        Facets facetsAnno = getFacetsAnnotation(info);
        TypedXmlWriter restriction = getRestriction(info, attr, null);
        addFacets(facetsAnno, restriction);
    }

    public static <T, C> void addFacets(Facets facetsAnno,
            TypedXmlWriter restriction) {

        Map<String, List<String>> facets = null;
        try {
            facets = getDefinedFacets(facetsAnno);
        } catch (Exception ex) {
            logger.log(Level.WARNING,
                    "Unable to add XSD Facets in Schema generated by JAXB.", ex);
            return;
        }

        for (String facetName : facets.keySet()) {
            for (String facetValue : facets.get(facetName)) {
                logger.fine("Adding XSD-Facets schema restriction: "
                        + new QName(NS_XSD, facetName));
                restriction._element(new QName(NS_XSD, facetName),
                        TypedXmlWriter.class)._attribute("value", facetValue);
            }
        }
    }

    public static <T, C> boolean hasFacets(ValuePropertyInfo<T, C> vp) {
        Facets facets = getFacetsAnnotation(vp);
        return hasFacets(facets);
    }

    public static <T, C> boolean hasFacets(TypeRef<T, C> t) {
        Facets facets = getFacetsAnnotation(t);
        return hasFacets(facets);
    }

    public static <T, C> boolean hasFacets(AttributePropertyInfo<T, C> ap) {
        Facets facets = getFacetsAnnotation(ap);
        return hasFacets(facets);
    }

    public static <T, C> boolean hasFacets(Facets facets) {
        if (facets == null)
            return false;

        try {
            Map<String, List<String>> definedFacets = getDefinedFacets(facets);
            return definedFacets.size() > 0;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to get defined XSD Facets", e);
            return false;
        }
    }

    public static <T> void addXsdAnnotations(T type, TypedXmlWriter w) {
        if (!hasXsdAnnotations(type))
            return;

        javax.xml.bind.annotation.Annotation anno = getXsdAnnotationAnnotation(type);
        addXsdAnnotations(anno, w);
    }

    public static <T, C> void addXsdAnnotations(Set<ClassInfo<T, C>> classes,
            Set<EnumLeafInfo<T, C>> enums, Set<ArrayInfo<T, C>> arrays,
            TypedXmlWriter w) {
        Set<Package> annotatedPackages = new HashSet<Package>();
        for (ClassInfo<T, C> c : classes) {
            Class<?> cl = (Class<?>) c.getType();
            Package pkg = cl.getPackage();
            annotatedPackages.add(pkg);
        }
        for (EnumLeafInfo<T, C> c : enums) {
            Class<?> cl = (Class<?>) c.getType();
            Package pkg = cl.getPackage();
            annotatedPackages.add(pkg);
        }
        for (ArrayInfo<T, C> c : arrays) {
            Class<?> cl = (Class<?>) c.getType();
            Package pkg = cl.getPackage();
            annotatedPackages.add(pkg);
        }
        for (Package p : annotatedPackages) {
            XmlSchemaEnhancer.addXsdAnnotations(p, w);
        }
    }

    public static <T, C> void addXsdAnnotations(ClassInfo<T, C> ci,
            TypedXmlWriter w) {
        if (!hasXsdAnnotations(ci))
            return;

        javax.xml.bind.annotation.Annotation anno = getXsdAnnotationAnnotation(ci);
        addXsdAnnotations(anno, w);
    }

    public static <T, C> void addXsdAnnotations(
            AttributePropertyInfo<T, C> _info, LocalAttribute _attr) {
        if (!hasXsdAnnotations(_info))
            return;

        javax.xml.bind.annotation.Annotation anno = getXsdAnnotationAnnotation(_info);
        addXsdAnnotations(anno, _attr);
    }

    public static <T, C> void addXsdAnnotations(TypeRef<T, C> t, LocalElement e) {
        if (!hasXsdAnnotations(t))
            return;

        javax.xml.bind.annotation.Annotation anno = getXsdAnnotationAnnotation(t);
        addXsdAnnotations(anno, e);
    }

    public static <T, C> void addXsdAnnotations(
            javax.xml.bind.annotation.Annotation anno, TypedXmlWriter obj) {
        TypedXmlWriter annoEl = getXsdAnnotation(obj, anno.id(),
                anno.attributes());
        for (AppInfo info : anno.appinfo()) {
            TypedXmlWriter w = annoEl._element(new QName(NS_XSD, "appinfo"),
                    TypedXmlWriter.class);
            if (info.source() != null && !info.source().equals("")) {
                w._attribute(new QName("source"), info.source());
            }
            /* Use XML parser to allow XML content in appinfo */
            writeXMLOrPCData(w, info.value());
        }
        for (Documentation doc : anno.documentation()) {
            TypedXmlWriter w = annoEl._element(new QName(NS_XSD,
                    "documentation"), TypedXmlWriter.class);
            if (doc.source() != null && !doc.source().equals("")) {
                w._attribute(new QName("source"), doc.source());
            }
            if (doc.lang() != null && !doc.lang().equals("")) {
                w._attribute(new QName(NS_XML, "lang"), doc.lang());
            }
            /* Use XML parser to allow XML content in documentation */
            w._pcdata(doc.value());
        }
    }

    /**
     * Try parsing a value as an XML root element. Returns a corresponding XML Document 
     * if the string value is a valid XML root element, otherwise null.
     * @param value
     * @return
     */
    private static Document parseValueAsXML(String value) {
        try {
            if(value == null || !value.trim().startsWith("<")) {
                return null;
            }
            DocumentBuilder dBuilder = XML_FACTORY.newDocumentBuilder();
            Document doc = dBuilder.parse(new ByteArrayInputStream(value.getBytes()));
            doc.getDocumentElement().normalize();
            logger.fine("Treating string as valid XML: '" + value + "'");
            return doc;
        } catch (Exception e) {
            logger.fine("Cannot parse value as XML, treating as regular string: '" + value + "'");
            return null;
        }
    }

    private static void writeXMLOrPCData(TypedXmlWriter w, String value) {
        Document doc = parseValueAsXML(value);
        if(doc == null) {
            w._pcdata(value);
            return;
        }
        try {
            writeXML(w, value);
        } catch (Exception e) {
            logger.info("Unable to write XML data to TXW2 serializer: " + e);
            w._pcdata(value);
        }
    }

    private static void writeXML(final TypedXmlWriter w, String value) throws Exception {
        TXWSerializer ser = (TXWSerializer)ResultFactory.createSerializer(new TXWResult(w));
        new DOMtoTXW(w).convert(value);
    }

    public static <T, C> boolean hasXsdAnnotations(ClassInfo<T, C> ci) {
        javax.xml.bind.annotation.Annotation anno = getXsdAnnotationAnnotation(ci);
        return anno != null;
    }

    public static <T> boolean hasXsdAnnotations(T type) {
        javax.xml.bind.annotation.Annotation anno = getXsdAnnotationAnnotation(type);
        return anno != null;
    }

    public static <T, C> boolean hasXsdAnnotations(TypeRef<T, C> t) {
        javax.xml.bind.annotation.Annotation anno = getXsdAnnotationAnnotation(t);
        return anno != null;
    }

    public static <T, C> boolean hasXsdAnnotations(
            AttributePropertyInfo<T, C> ap) {
        javax.xml.bind.annotation.Annotation anno = getXsdAnnotationAnnotation(ap);
        return anno != null;
    }

    public static <T, C> boolean writeCustomOccurs(TypeRef<T, C> t,
            LocalElement e, boolean isOptional, boolean repeated) {
        MaxOccurs max = null;
        MinOccurs min = null;
        try {
            max = (MaxOccurs) getAnnotationOfProperty(t.getSource(),
                    MaxOccurs.class);
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get @MaxOccurs annotation from type " + t, e2);
        }
        try {
            min = (MinOccurs) getAnnotationOfProperty(t.getSource(),
                    MinOccurs.class);
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get @MinOccurs annotation from type " + t, e2);
        }

        if (min == null && max == null)
            return false;

        if (min != null) {
            int value = (int) min.value();
            e.minOccurs(value);
        } else if (isOptional) {
            e.minOccurs(0);
        }

        if (max != null) {
            int value = (int) max.value();
            e.maxOccurs(value);
        } else if (repeated) {
            e.maxOccurs("unbounded");
        }

        return true;
    }

    /* PRIVATE HELPER METHODS */

    private static <T, C> javax.xml.bind.annotation.Annotation getXsdAnnotationAnnotation(
            ClassInfo<T, C> ci) {
        return getXsdAnnotationAnnotation(ci.getType());
    }

    private static <T, C> javax.xml.bind.annotation.Annotation getXsdAnnotationAnnotation(
            EnumConstant c) {
        Documentation doc = AnnotationUtils
                .getDocumentation((EnumConstant) c);
        return XmlSchemaEnhancer
                .getXsdAnnotationAnnotation(null, doc, null);
    }

    private static <T, C> javax.xml.bind.annotation.Annotation getXsdAnnotationAnnotation(
            Class<?> clazz) {
        javax.xml.bind.annotation.Annotation anno = clazz
                .getAnnotation(javax.xml.bind.annotation.Annotation.class);
        AppInfo appinfo = clazz.getAnnotation(AppInfo.class);
        Documentation doc = clazz.getAnnotation(Documentation.class);
        return XmlSchemaEnhancer.getXsdAnnotationAnnotation(anno, doc,
                appinfo);
    }
    public static <T> javax.xml.bind.annotation.Annotation getXsdAnnotationAnnotation(
            T type) {
        javax.xml.bind.annotation.Annotation anno = null;
        if (type instanceof Class<?>) {
            Class<?> clazz = (Class<?>) type;
            anno = clazz
                    .getAnnotation(javax.xml.bind.annotation.Annotation.class);
            AppInfo appinfo = clazz.getAnnotation(AppInfo.class);
            Documentation doc = clazz.getAnnotation(Documentation.class);
            return XmlSchemaEnhancer.getXsdAnnotationAnnotation(anno, doc,
                    appinfo);
        } else if (type instanceof Package) {
            Package pkg = (Package) type;
            anno = pkg
                    .getAnnotation(javax.xml.bind.annotation.Annotation.class);
            AppInfo appinfo = pkg.getAnnotation(AppInfo.class);
            Documentation doc = pkg.getAnnotation(Documentation.class);
            return XmlSchemaEnhancer.getXsdAnnotationAnnotation(anno, doc,
                    appinfo);
        } else {
            if (type instanceof EnumConstant) {
                Documentation doc = AnnotationUtils
                        .getDocumentation((EnumConstant) type);
                return XmlSchemaEnhancer
                        .getXsdAnnotationAnnotation(null, doc, null);
            }
        }
        return null;
    }

    private static <T, C> javax.xml.bind.annotation.Annotation getXsdAnnotationAnnotation(
            TypeRef<T, C> t) {
        return getXsdAnnotationAnnotation(t.getSource());
    }

    private static <T, C> javax.xml.bind.annotation.Annotation getXsdAnnotationAnnotation(
            AttributePropertyInfo<T, C> t) {
        return getXsdAnnotationAnnotation(t.getSource());
    }

    private static <T, C> javax.xml.bind.annotation.Annotation getXsdAnnotationAnnotation(
            PropertyInfo<T, C> t) {
        javax.xml.bind.annotation.Annotation anno = null;
        AppInfo appinfo = null;
        Documentation doc = null;

        try {
            Object value = getAnnotationOfProperty(t,
                    javax.xml.bind.annotation.Annotation.class);
            if (value instanceof javax.xml.bind.annotation.Annotation) {
                anno = (javax.xml.bind.annotation.Annotation) value;
            }
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get XSD Annotation annotation from type " + t,
                    e2);
        }

        try {
            Object value = getAnnotationOfProperty(t, AppInfo.class);
            if (value instanceof AppInfo) {
                appinfo = (AppInfo) value;
            }
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get XSD AppInfo annotation from type " + t, e2);
        }

        try {
            Object value = getAnnotationOfProperty(t, Documentation.class);
            if (value instanceof Documentation) {
                doc = (Documentation) value;
            }
        } catch (Exception e2) {
            logger.log(
                    Level.WARNING,
                    "Unable to get XSD Documentation annotation from type " + t,
                    e2);
        }

        return getXsdAnnotationAnnotation(anno, doc, appinfo);
    }

    protected static <T, C> javax.xml.bind.annotation.Annotation getXsdAnnotationAnnotation(
            javax.xml.bind.annotation.Annotation _anno, Documentation _doc,
            AppInfo _appinfo) {
        ClassLoader cl = _anno != null ? _anno.getClass().getClassLoader()
                : _doc != null ? _doc.getClass().getClassLoader()
                        : _appinfo != null ? _appinfo.getClass()
                                .getClassLoader() : null;

        // jpell:   if none of Annotation, AppInfo or Documentation are not null, whats
        //          the point of falling back to the system classloader
        // whummer: Please do not edit - Java7 users have encountered problems here.
        //          Fallback to system classloader is necessary for compatibility with
        //          Java7 JAXB bootstrapping/overriding mechanism.
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }

        final Map<String, Object> annoValues = new HashMap<String, Object>();
        annoValues.put("appinfo", new AppInfo[] {});
        annoValues.put("attributes", new String[] {});
        annoValues.put("documentation", new Documentation[] {});
        InvocationHandler h = new InvocationHandler() {
            public Object invoke(Object o, Method m, Object[] args)
                    throws Throwable {
                return annoValues.get(m.getName());
            }
        };

        javax.xml.bind.annotation.Annotation anno = (javax.xml.bind.annotation.Annotation) Proxy
                .newProxyInstance(
                        cl,
                        new Class<?>[] { javax.xml.bind.annotation.Annotation.class },
                        h);

        boolean hasAnno = false;

        try {
            if (_anno instanceof javax.xml.bind.annotation.Annotation) {
                annoValues.put("id", _anno.id());
                annoValues.put("appinfo", _anno.appinfo());
                annoValues.put("attributes", _anno.attributes());
                annoValues.put("documentation", _anno.documentation());
                hasAnno = true;
            }
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get XSD Annotation annotation from type "
                            + _anno, e2);
        }

        try {
            if (_appinfo instanceof AppInfo) {
                AppInfo[] appinfos = (AppInfo[]) annoValues.get("appinfo");
                annoValues.put("appinfo", concat(appinfos, _appinfo));
                hasAnno = true;
            }
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get XSD AppInfo annotation from type "
                            + _appinfo, e2);
        }

        try {
            if (_doc instanceof Documentation) {
                Documentation[] docs = (Documentation[]) annoValues
                        .get("documentation");
                annoValues.put("documentation", concat(docs, _doc));
                hasAnno = true;
            }
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get XSD Documentation annotation from type "
                            + _doc, e2);
        }

        return hasAnno ? anno : null;
    }

    private static <T, C> Facets getFacetsAnnotation(TypeRef<T, C> t) {
        if (!t.getTarget().isSimpleType())
            return null;

        try {
            Object value = getAnnotationOfProperty(t.getSource(), Facets.class);
            if (value instanceof Facets)
                return (Facets) value;
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get Facets annotation from type " + t, e2);
        }
        return null;
    }

    private static <T, C> Facets getFacetsAnnotation(ValuePropertyInfo<T, C> vp) {
        if (!vp.getTarget().isSimpleType())
            return null;

        try {
            Object value = getAnnotationOfProperty(vp.getSource(), Facets.class);
            if (value instanceof Facets)
                return (Facets) value;
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get Facets annotation from type " + vp, e2);
        }
        return null;
    }

    private static <T, C> Facets getFacetsAnnotation(
            AttributePropertyInfo<T, C> t) {
        if (!t.getTarget().isSimpleType())
            return null;

        try {
            Object value = getAnnotationOfProperty(t.getSource(), Facets.class);
            if (value instanceof Facets)
                return (Facets) value;
        } catch (Exception e2) {
            logger.log(Level.WARNING,
                    "Unable to get Facets annotation from type " + t, e2);
        }
        return null;
    }

    private static <T, C> Object getAnnotationOfProperty(
            PropertyInfo<T, C> info, Class<? extends Annotation> annoClass)
            throws Exception {
        if (annoClass == Facets.class && info.hasAnnotation(Facets.class)) {
            return info.readAnnotation(Facets.class);
        } else if (annoClass == MaxOccurs.class && info.hasAnnotation(MaxOccurs.class)) {
            return info.readAnnotation(MaxOccurs.class);
        } else if (annoClass == MinOccurs.class && info.hasAnnotation(MinOccurs.class)) {
            return info.readAnnotation(MinOccurs.class);
        } else if (info.parent() == null) {
            return null;
        } else if (!(info.parent().getType() instanceof Class<?>)) {
            return null;
        }
        
        Class<?> parent = (Class<?>) info.parent().getType();
        String name = info.getName();
        return getAnnotationOfProperty(parent, name, annoClass);
    }

    protected static <T extends Annotation> T getAnnotationOfProperty(
            Class<?> parent, String fieldName, Class<T> annoClass)
            throws Exception {
        try {
            Field field = findAnnotatedField(parent, fieldName);
            if (field == null)
                return null;
            Object a = getAnnotation(field, annoClass);
            return (T) a;
        } catch (Exception e) {
            throw new RuntimeException("Could not get annotation '"
                    + annoClass.getSimpleName() + "' of field " + fieldName
                    + " of class " + parent, e);
        }
    }

    private static Field findAnnotatedField(Class<?> parent, String fieldName) {
        Field field = null;
        for (Field f : parent.getDeclaredFields()) {
            if (f.getName().equals(fieldName)) {
                field = f;
                break;
            } else {
                if (getAnnotation(f, XmlElement.class) != null) {
                    XmlElement e = (XmlElement) getAnnotation(f,
                            XmlElement.class);
                    if (fieldName.equals(e.name())) {
                        field = f;
                        break;
                    }
                }
                if (getAnnotation(f, XmlAttribute.class) != null) {
                    XmlAttribute a = (XmlAttribute) getAnnotation(f,
                            XmlAttribute.class);
                    if (fieldName.equals(a.name())) {
                        field = f;
                        break;
                    }
                }
            }
        }
        return field;
    }

    protected static <T extends Annotation> T getAnnotation(AccessibleObject field,
            Class<T> annoClass) {
        for (Annotation anno : field.getAnnotations()) {
            try {
                return annoClass.cast(anno);
            } catch (Exception e) {
                /* swallow */
            }
            if (anno instanceof Proxy) {
                try {
                    Object handler = Proxy.getInvocationHandler(anno);
                    if (handler instanceof InvocationHandler) {
                        T annoObj = convertToAnnotation(
                                (InvocationHandler) handler, annoClass);
                        return (T) annoObj;
                    }
                } catch (Exception e) {
                    /* swallow */
                }
            }
            if (annoClass.equals(anno.getClass())
                    || annoClass.isAssignableFrom(anno.getClass())) {
                return (T) anno;
            }
        }
        return null;
    }

    private static <T extends Annotation> T convertToAnnotation(
            final InvocationHandler handler, final Class<T> expectedClass) {
        try {
            Field f = InvocationHandler.class.getDeclaredField("memberValues");
            f.setAccessible(true);
            Map<String, Object> memberValues = (Map<String, Object>) f
                    .get(handler);
            Field f1 = InvocationHandler.class.getDeclaredField("type");
            f1.setAccessible(true);
            final Class<?> type = (Class<?>) f1.get(handler);
            if (!expectedClass.getName().equals(type.getName())) {
                throw new RuntimeException("Not the expected annotation type: "
                        + type + " != " + expectedClass);
            }
            T anno = (T) Proxy.newProxyInstance(expectedClass.getClassLoader(),
                    new Class[] { expectedClass }, new InvocationHandler() {
                        public Object invoke(Object proxy, Method method,
                                Object[] args) {
                            Object o = null;
                            try {
                                o = handler.invoke(proxy, method, args);
                                if (o != null) {
                                    Class<?> componentClass = o.getClass();
                                    if (componentClass.isArray()) {
                                        componentClass = componentClass
                                                .getComponentType();
                                    }
                                    if (!componentClass.isPrimitive()
                                            && !componentClass.getName()
                                                    .startsWith("java.lang")) {
                                        ClassLoader cl = expectedClass
                                                .getClassLoader() != null ? expectedClass
                                                .getClassLoader() : ClassLoader
                                                .getSystemClassLoader();
                                        String name = componentClass.getName();
                                        if (cl.loadClass(name) != componentClass) {
                                            /*
                                             * we need to import/convert the
                                             * class into the classloader of
                                             * expectedClass
                                             */
                                            if (componentClass.getName()
                                                    .endsWith("WhiteSpace")) {
                                                o = WhiteSpace.valueOf(o
                                                        .toString());
                                            } else {
                                                logger.warning("Unknown/Unexpected class "
                                                        + componentClass);
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable e) {
                                logger.log(Level.WARNING, "", e);
                            }
                            return o;
                        }
                    });
            return anno;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to convert AnnotationInvocationHandler to annotation.",
                    e);
        }
    }

    private static <T, C> TypedXmlWriter getRestriction(
            AttributePropertyInfo<T, C> info, TypedXmlWriter obj) {
        return getRestriction(info, obj, null);
    }
    private static <T, C> TypedXmlWriter getRestriction(
            AttributePropertyInfo<T, C> info, TypedXmlWriter obj,
            TypedXmlWriter w) {
        return getRestriction(info.getTarget().getTypeName(), obj, w);
    }
    private static <T, C> TypedXmlWriter getRestriction(
            ValuePropertyInfo<T, C> info, TypedXmlWriter obj, TypedXmlWriter w) {
        return getRestriction(info.getTarget().getTypeName(), obj, w);
    }
    private static <T, C> TypedXmlWriter getRestriction(TypeRef<T, C> t,
            TypedXmlWriter obj, TypedXmlWriter w) {
        if (w != null) {
            return w;
        }
        QName schemaType = t.getSource() == null ? null : t.getSource()
                .getSchemaType();
        if (schemaType == null)
            schemaType = t.getTarget().getTypeName();
        return getRestriction(schemaType, obj, w);
    }

    private static  <T, C> TypedXmlWriter getRestriction(
            QName typeName, TypedXmlWriter obj, TypedXmlWriter w) {
        if (w != null) {
            return w;
        }
        if(typeName.toString().contains("int")) {
            Thread.dumpStack();
        }
        TypedXmlWriter st = obj._element(new QName(NS_XSD, "simpleType"),
                TypedXmlWriter.class);
        TypedXmlWriter r = st._element(new QName(NS_XSD, "restriction"),
                TypedXmlWriter.class);
        r._attribute("base", typeName);
        return r;
    }

    private static <T, C> TypedXmlWriter getXsdAnnotation(TypedXmlWriter obj,
            String annoID, String[] otherAttributes) {
        TypedXmlWriter anno = obj._element(new QName(NS_XSD, "annotation"),
                TypedXmlWriter.class);
        if (annoID != null && !annoID.trim().isEmpty()) {
            anno._attribute(new QName("id"), annoID);
        }
        if (otherAttributes != null && otherAttributes.length > 0) {
            // TODO implement!
            logger.warning("Support for arbitrary attributes on xsd:annotation element not implemented yet.");
        }
        return anno;
    }

    protected static Map<String, List<String>> getDefinedFacets(
            Facets facetsAnnotation) throws Exception {
        List<Method> annoMethods = new LinkedList<Method>();
        Map<String, List<String>> result = new HashMap<String, List<String>>();

        if (facetsAnnotation == null)
            return result;

        for (Method m : Facets.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(FacetDefinition.class))
                annoMethods.add(m);
        }

        for (Method m : annoMethods) {
            /* additional code suggested by Jason Pell (jason@pellcorp.com) */
            FacetDefinition facetDefinition = m
                    .getAnnotation(FacetDefinition.class);
            String facetName = m.getName();
            if (facetDefinition.xsdAttributeName() != null
                    && facetDefinition.xsdAttributeName().length() > 0) {
                facetName = facetDefinition.xsdAttributeName();
            }
            /* end additional code */

            Object value = m.invoke(facetsAnnotation);
            Object defaultValue = m.getDefaultValue();
            if (value != null && !value.equals(defaultValue)) {

                if (!result.containsKey(facetName)) {
                    result.put(facetName, new LinkedList<String>());
                }
                if (value instanceof String[]) {
                    for (String s : (String[]) value)
                        result.get(facetName).add(s);
                } else {
                    result.get(facetName).add("" + value);
                }
            }
        }

        return result;
    }

    /**
     * Helper method to concatenate two arrays.
     * 
     * @param first
     * @param second
     * @return
     */
    public static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * Helper method to concatenate an arrays with an additional value.
     * 
     * @param first
     * @param second
     * @return
     */
    public static <T> T[] concat(T[] first, T second) {
        T[] result = Arrays.copyOf(first, first.length + 1);
        System.arraycopy(first, 0, result, 0, first.length);
        result[result.length - 1] = second;
        return result;
    }
    
    
    
    
    
    /* COMPATIBILITY METHODS TO DEAL WITH com.sun.xml.internal.bind.* */
    

//    /** for compatibility with Java 1.7 */
//    public static <T, C> boolean hasExtendedAnnotations(
//            com.sun.xml.internal.bind.v2.model.core.AttributePropertyInfo<T, C> info) {
//        return hasFacets(info) || hasXsdAnnotations(info);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> boolean hasExtendedAnnotations(
//            com.sun.xml.internal.bind.v2.model.core.TypeRef<T, C> t) {
//        return hasFacets(t) || hasXsdAnnotations(t);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addFacets(
//            com.sun.xml.internal.bind.v2.model.core.ValuePropertyInfo<T, C> vp,
//            com.sun.xml.internal.bind.v2.schemagen.xmlschema.SimpleRestriction sr) {
//        XmlSchemaEnhancerJava7.addFacets(vp, sr);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addFacets(
//            com.sun.xml.internal.bind.v2.model.core.TypeRef<T, C> t,
//            com.sun.xml.internal.bind.v2.schemagen.xmlschema.LocalElement e) {
//        XmlSchemaEnhancerJava7.addFacets(t, e);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addFacets(
//            com.sun.xml.internal.bind.v2.model.core.AttributePropertyInfo<T, C> info,
//            com.sun.xml.internal.bind.v2.schemagen.xmlschema.LocalAttribute attr) {
//        XmlSchemaEnhancerJava7.addFacets(info, attr);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> boolean hasFacets(
//            com.sun.xml.internal.bind.v2.model.core.TypeRef<T, C> t) {
//        return XmlSchemaEnhancerJava7.hasFacets(t);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> boolean hasFacets(
//            com.sun.xml.internal.bind.v2.model.core.AttributePropertyInfo<T, C> ap) {
//        return XmlSchemaEnhancerJava7.hasFacets(ap);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addXsdAnnotations(T type,
//            com.sun.xml.internal.txw2.TypedXmlWriter w) {
//        XmlSchemaEnhancerJava7.addXsdAnnotations(type, w);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addXsdAnnotations(
//            Set<com.sun.xml.internal.bind.v2.model.core.ClassInfo<T, C>> classes,
//            Set<com.sun.xml.internal.bind.v2.model.core.EnumLeafInfo<T, C>> enums,
//            Set<com.sun.xml.internal.bind.v2.model.core.ArrayInfo<T, C>> arrays,
//            com.sun.xml.internal.txw2.TypedXmlWriter w) {
//        Set<Package> annotatedPackages = new HashSet<Package>();
//        for (com.sun.xml.internal.bind.v2.model.core.ClassInfo<T, C> c : classes) {
//            Class<?> cl = (Class<?>) c.getType();
//            Package pkg = cl.getPackage();
//            annotatedPackages.add(pkg);
//        }
//        for (com.sun.xml.internal.bind.v2.model.core.EnumLeafInfo<T, C> c : enums) {
//            Class<?> cl = (Class<?>) c.getType();
//            Package pkg = cl.getPackage();
//            annotatedPackages.add(pkg);
//        }
//        for (com.sun.xml.internal.bind.v2.model.core.ArrayInfo<T, C> c : arrays) {
//            Class<?> cl = (Class<?>) c.getType();
//            Package pkg = cl.getPackage();
//            annotatedPackages.add(pkg);
//        }
//        for (Package p : annotatedPackages) {
//            XmlSchemaEnhancerJava7.addXsdAnnotations(p, w);
//        }
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addXsdAnnotations(
//            com.sun.xml.internal.bind.v2.model.core.ClassInfo<T, C> ci,
//            com.sun.xml.internal.txw2.TypedXmlWriter w) {
//        XmlSchemaEnhancerJava7.addXsdAnnotations(ci, w);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addXsdAnnotations(
//            com.sun.xml.internal.bind.v2.model.core.AttributePropertyInfo<T, C> _info, 
//            com.sun.xml.internal.bind.v2.schemagen.xmlschema.LocalAttribute _attr) {
//        XmlSchemaEnhancerJava7.addXsdAnnotations(_info, _attr);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addXsdAnnotations(
//            com.sun.xml.internal.bind.v2.model.core.TypeRef<T, C> _info, 
//            com.sun.xml.internal.bind.v2.schemagen.xmlschema.LocalElement _el) {
//        XmlSchemaEnhancerJava7.addXsdAnnotations(_info, _el);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> void addXsdAnnotations(
//            javax.xml.bind.annotation.Annotation anno, 
//            com.sun.xml.internal.txw2.TypedXmlWriter obj) {
//        XmlSchemaEnhancerJava7.addXsdAnnotations(anno, obj);
//    }
//    /** for compatibility with Java 1.7 */
//    public static <T, C> boolean writeCustomOccurs(
//            com.sun.xml.internal.bind.v2.model.core.TypeRef<T, C> t,
//            com.sun.xml.internal.bind.v2.schemagen.xmlschema.LocalElement e,
//            boolean isOptional, boolean repeated) {
//        return XmlSchemaEnhancerJava7.writeCustomOccurs(t, e, isOptional,
//                repeated);
//    }

}
