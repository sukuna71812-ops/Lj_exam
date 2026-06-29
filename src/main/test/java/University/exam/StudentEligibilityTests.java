package University.exam;

import University.exam.service.StudentFileParserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StudentEligibilityTests {

    private final StudentFileParserService service = new StudentFileParserService();

    @Test
    void testParseCsvWithHeaders() throws Exception {
        String csv = "Enrollment No,Student Name,Semester,Division,Roll No\n" +
                "240045012101,Raj Patel,3,A,13\n" +
                "240045012102,Nirav Shah,3,B,14\n";
        MockMultipartFile file = new MockMultipartFile("file", "students.csv", "text/csv", csv.getBytes());
        List<StudentFileParserService.ParsedStudent> students = service.parseFile(file);
        
        assertEquals(2, students.size());
        assertEquals("240045012101", students.get(0).getEnrollmentNo());
        assertEquals("Raj Patel", students.get(0).getStudentName());
        assertEquals("3", students.get(0).getSemester());
        assertEquals("A", students.get(0).getDivision());
        assertEquals("A13", students.get(0).getRollNo());
    }

    @Test
    void testParseCsvNoHeaders() throws Exception {
        String csv = "240045012101,Raj Patel,A,3\n" +
                "240045012102,Nirav Shah,B,3\n";
        MockMultipartFile file = new MockMultipartFile("file", "students.csv", "text/csv", csv.getBytes());
        List<StudentFileParserService.ParsedStudent> students = service.parseFile(file);
        
        assertEquals(2, students.size());
        assertEquals("240045012101", students.get(0).getEnrollmentNo());
        assertEquals("Raj Patel", students.get(0).getStudentName());
    }

    @Test
    void testParsePdfWithSingleSpaces() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            
            try (org.apache.pdfbox.pdmodel.PDPageContentStream contentStream = 
                    new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                contentStream.beginText();
                contentStream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD, 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText("1 Neetyam Patel 24004501210260 D 30 7405406038 5406038 Semester 3");
                contentStream.newLineAtOffset(0, -20);
                contentStream.showText("2 Aaray Shah 24004501210261 A 12 9876543210 6543210 Semester 3");
                contentStream.endText();
            }
            
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            
            MockMultipartFile file = new MockMultipartFile("file", "students.pdf", "application/pdf", out.toByteArray());
            List<StudentFileParserService.ParsedStudent> students = service.parseFile(file);
            
            assertEquals(2, students.size());
            assertEquals("24004501210260", students.get(0).getEnrollmentNo());
            assertEquals("Neetyam Patel", students.get(0).getStudentName());
            assertEquals("D", students.get(0).getDivision());
            assertEquals("D30", students.get(0).getRollNo());
            
            assertEquals("24004501210261", students.get(1).getEnrollmentNo());
            assertEquals("Aaray Shah", students.get(1).getStudentName());
            assertEquals("A", students.get(1).getDivision());
            assertEquals("A12", students.get(1).getRollNo());
        }
    }

    @Test
    void testParsePdfWithLayout2() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            
            try (org.apache.pdfbox.pdmodel.PDPageContentStream contentStream = 
                    new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                contentStream.beginText();
                contentStream.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD, 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText("4 A13 24004501210453 THUMMAR DIYABEN SANJAYBHAI 25 7 8 40");
                contentStream.newLineAtOffset(0, -20);
                contentStream.showText("5 B15 24004501210083 FALDU KRUTIBEN RAKESHBHAI 15 8 8 31");
                contentStream.endText();
            }
            
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            
            MockMultipartFile file = new MockMultipartFile("file", "students.pdf", "application/pdf", out.toByteArray());
            List<StudentFileParserService.ParsedStudent> students = service.parseFile(file);
            
            assertEquals(2, students.size());
            assertEquals("24004501210453", students.get(0).getEnrollmentNo());
            assertEquals("THUMMAR DIYABEN SANJAYBHAI", students.get(0).getStudentName());
            assertEquals("A", students.get(0).getDivision());
            assertEquals("A13", students.get(0).getRollNo());
            
            assertEquals("24004501210083", students.get(1).getEnrollmentNo());
            assertEquals("FALDU KRUTIBEN RAKESHBHAI", students.get(1).getStudentName());
            assertEquals("B", students.get(1).getDivision());
            assertEquals("B15", students.get(1).getRollNo());
        }
    }
}