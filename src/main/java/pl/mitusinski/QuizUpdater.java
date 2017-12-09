package pl.mitusinski;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.bind.*;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Created by Łukasz on 18.05.2017.
 */
public class QuizUpdater extends Thread {

    private static final String QUERY_TITLE = "title";
    private static final String QUERY_DESCRIPTION = ".quizLead > p";
    private static final String QUERY_HAS_LINK = "form > input[type='submit']";

    private static final String QUIZ_URL = "http://wiadomosci.gazeta.pl/wiadomosci/13,129662,{id},x.html";
    private static final String ID_TAG = "{id}";
    private static final int MAX_FAILURES = 10;

    // języki
    public static final String UNKNOWN = "UNKNOWN";
    public static final String ES = "ES";
    public static final String PL = "PL";

    private BooleanProperty sync = new SimpleBooleanProperty(false);
    private int count = 0;

    QuizUpdater() {
    }

    public void update() {
        start();
    }

    @Override
    public void run() {
        sync.set(true);
        QuizList quizList = getQuizList();
        List<Quiz> list = quizList.getQuizList();
        List<Quiz> newList = new ArrayList<>();
        list.sort(Comparator.comparing(q2 -> new Integer(q2.getId())));
        int next = Integer.parseInt(list.get(0).getId());

        for (Quiz q : list) {
            next++;
            while (Integer.parseInt(q.getId()) > next) {
                Optional<Quiz> quizData = getQuizData(next);
                quizData.ifPresent(quiz -> {
                    if (quiz.getCorrupted().equals("N")){
                        count++;
                    }
                    newList.add(quiz);
                });
                next++;
            }

            if (q.getLanguage() == null || q.getLanguage().isEmpty() || q.getLanguage().equals(UNKNOWN)) {
                String language = getLanguage(q.getCorrupted(), q.getTitle());
                q.setLanguage(language);
            }

            newList.add(q);
        }

        setQuizList(new QuizList(newList));
        AtomicInteger failures = new AtomicInteger();
        while (failures.get() < MAX_FAILURES) {
            next++;
            Optional<Quiz> quizData = getQuizData(next);

            quizData.ifPresent(quiz -> {
                if (!"N".equals(quiz.getCorrupted())) {
                    failures.getAndIncrement();
                } else {
                    failures.set(0);
                    count++;
                    newList.add(quiz);
                }
            });
        }
        setQuizList(new QuizList(newList));
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        sync.set(false);
    }

    private QuizList getQuizList() {
        File file = new File(Main.DATA_FILE);
        QuizList quizList = new QuizList();
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = sf.newSchema(this.getClass().getResource(Main.DATA_FILE_XSD));

            JAXBContext jaxb = JAXBContext.newInstance(QuizList.class);
            Unmarshaller unm = jaxb.createUnmarshaller();
            unm.setSchema(schema);
            unm.setEventHandler(new QuizFileValidationEventHandler());
            quizList = (QuizList) unm.unmarshal(file);
        } catch (JAXBException | SAXException e) {
            e.printStackTrace();
        }
        return quizList;
    }

    private void setQuizList(QuizList quizList) {
        File file = new File(Main.DATA_FILE);
        JAXBContext jaxb;
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = sf.newSchema(this.getClass().getResource(Main.DATA_FILE_XSD));

            jaxb = JAXBContext.newInstance(QuizList.class);
            Marshaller msh = jaxb.createMarshaller();
            msh.setSchema(schema);
            msh.setEventHandler(new QuizFileValidationEventHandler());
            msh.marshal(quizList, file);
        } catch (JAXBException | SAXException e) {
            e.printStackTrace();
        }
    }

    private Optional<Quiz> getQuizData(int id) {
        String url = getUrl(id);
        try {
            Document doc = Jsoup.connect(url).get();

            String title = doc.select(QUERY_TITLE).text();
            String desc = doc.select(QUERY_DESCRIPTION).text();
            boolean hasLink = doc.select(QUERY_HAS_LINK).size() > 0;
            Date added = new Date();
            String corrupted = !title.isEmpty() && hasLink ? "N" : "Y";
            String language = getLanguage(corrupted, title);
            return Optional.of(new Quiz(Integer.toString(id), title, desc, added.toString(), corrupted, language));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return Optional.empty();
    }

    private String getLanguage(String corrupted, String title) {
        if (corrupted.equals("Y")) {
            return UNKNOWN;
        }

        Pattern spanish = Pattern.compile("[¿¡ñáéíú]+");
        Pattern polish = Pattern.compile("[ąćłżźęśń]+"); // ó może być i w hiszpańskim i w polskim tekście

        if (spanish.matcher(title).find()) {
            return ES;
        }

        if (polish.matcher(title).find()) {
            return PL;
        }

        return UNKNOWN;
    }

    public BooleanProperty getSync() {
        return sync;
    }

    public static String getUrl(int id) {
        String quizId = String.format("%4s", Integer.toString(id)).replace(" ", "0");
        return QUIZ_URL.replace(ID_TAG, quizId);
    }

    public int getNewCount() {
        return count;
    }

    private class QuizFileValidationEventHandler implements ValidationEventHandler {

        public boolean handleEvent(ValidationEvent event) {
            System.out.println("\nEVENT");
            System.out.println("SEVERITY:  " + event.getSeverity());
            System.out.println("MESSAGE:  " + event.getMessage());
            System.out.println("LINKED EXCEPTION:  " + event.getLinkedException());
            System.out.println("LOCATOR");
            System.out.println("    LINE NUMBER:  " + event.getLocator().getLineNumber());
            System.out.println("    COLUMN NUMBER:  " + event.getLocator().getColumnNumber());
            System.out.println("    OFFSET:  " + event.getLocator().getOffset());
            System.out.println("    OBJECT:  " + event.getLocator().getObject());
            System.out.println("    NODE:  " + event.getLocator().getNode());
            System.out.println("    URL:  " + event.getLocator().getURL());
            return true;
        }
    }
}
