package University.exam.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StudentFileParserService {

    public static class ParsedStudent {
        private String enrollmentNo;
        private String studentName;
        private String division;
        private String semester;
        private String rollNo = "";

        public ParsedStudent() {}

        public ParsedStudent(String enrollmentNo, String studentName, String division, String semester) {
            this.enrollmentNo = enrollmentNo;
            this.studentName = studentName;
            this.division = division;
            this.semester = semester;
            this.rollNo = "";
        }

        public ParsedStudent(String enrollmentNo, String studentName, String division, String semester, String rollNo) {
            this.enrollmentNo = enrollmentNo;
            this.studentName = studentName;
            this.division = division;
            this.semester = semester;
            this.rollNo = rollNo;
        }

        public String getEnrollmentNo() { return enrollmentNo; }
        public void setEnrollmentNo(String enrollmentNo) { this.enrollmentNo = enrollmentNo; }
        public String getStudentName() { return studentName; }
        public void setStudentName(String studentName) { this.studentName = studentName; }
        public String getDivision() { return division; }
        public void setDivision(String division) { this.division = division; }
        public String getSemester() { return semester; }
        public void setSemester(String semester) { this.semester = semester; }
        public String getRollNo() { return rollNo; }
        public void setRollNo(String rollNo) { this.rollNo = rollNo; }

        @Override
        public String toString() {
            return "ParsedStudent{" +
                    "enrollmentNo='" + enrollmentNo + '\'' +
                    ", studentName='" + studentName + '\'' +
                    ", division='" + division + '\'' +
                    ", semester='" + semester + '\'' +
                    ", rollNo='" + rollNo + '\'' +
                    '}';
        }
    }

    public List<ParsedStudent> parseFile(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename();
        if (fileName == null) {
            throw new IllegalArgumentException("File name is null");
        }
        
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".pdf")) {
            return parsePdf(file.getInputStream());
        } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return parseExcel(file.getInputStream());
        } else if (lowerName.endsWith(".csv")) {
            return parseCsv(file.getInputStream());
        } else {
            throw new IllegalArgumentException("Unsupported file format. Please upload PDF, Excel, or CSV.");
        }
    }

    private List<ParsedStudent> parsePdf(InputStream inputStream) throws Exception {
        List<ParsedStudent> list = new ArrayList<>();
        PDDocument document = null;
        try {
            document = PDDocument.load(inputStream);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            
            if (text == null || text.trim().isEmpty()) {
                return list;
            }
            
            String[] lines = text.split("\\r?\\n");
            Pattern enrollPattern = Pattern.compile("\\b\\d{10,15}\\b");
            
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                // Skip obvious header or metadata lines
                String lower = line.toLowerCase();
                if (lower.contains("enrollment no") || lower.contains("student name") || 
                    lower.contains("college name") || lower.contains("university") || 
                    lower.contains("page ") || lower.contains("watermark")) {
                    continue;
                }
                
                Matcher matcher = enrollPattern.matcher(line);
                if (matcher.find()) {
                    String enrollmentNo = matcher.group();
                    
                    // Split the line into tokens by any whitespace
                    String[] tokens = line.split("\\s+");
                    
                    int enrollIdx = -1;
                    for (int i = 0; i < tokens.length; i++) {
                        if (tokens[i].contains(enrollmentNo)) {
                            enrollIdx = i;
                            break;
                        }
                    }
                    
                    String name = "";
                    String division = "";
                    String semester = "";
                    String rollNo = "";
                    
                    // Determine if there are potential name/division tokens before enrollment
                    boolean hasTokensBefore = false;
                    if (enrollIdx > 1) {
                        hasTokensBefore = true;
                    } else if (enrollIdx == 1 && !tokens[0].matches("\\d+")) {
                        hasTokensBefore = true;
                    }
                    
                    // Check if there is a division/roll code (e.g. "A13", "A") before enrollment
                    String beforeDiv = "";
                    if (hasTokensBefore) {
                        int start = (tokens[0].matches("\\d+")) ? 1 : 0;
                        for (int i = start; i < enrollIdx; i++) {
                            String t = tokens[i];
                            if (t.matches("^[a-zA-Z]\\d{0,3}$")) {
                                beforeDiv = t;
                                break;
                            }
                        }
                    }
                    
                    // Layout selection:
                    // If we have a division code before enrollment, OR if there are no tokens before enrollment,
                    // then the Name must be AFTER the enrollment number.
                    boolean nameIsAfter = (enrollIdx == 0 || (enrollIdx == 1 && tokens[0].matches("\\d+")) || !beforeDiv.isEmpty());
                    
                    if (nameIsAfter) {
                        // Layout 2: Name is after enrollment.
                        // Division is either beforeDiv (if found) or the first matching token after enrollment
                        if (!beforeDiv.isEmpty()) {
                            division = beforeDiv.substring(0, 1).toUpperCase();
                            if (beforeDiv.length() > 1) {
                                rollNo = beforeDiv.substring(1);
                            }
                        } else {
                            // scan after enrollment for division
                            for (int i = enrollIdx + 1; i < tokens.length; i++) {
                                String t = tokens[i];
                                if (t.matches("^[a-zA-Z]\\d{0,3}$")) {
                                    division = t.substring(0, 1).toUpperCase();
                                    if (t.length() > 1) {
                                        rollNo = t.substring(1);
                                    } else if (i + 1 < tokens.length && tokens[i + 1].matches("\\d+")) {
                                        rollNo = tokens[i + 1];
                                    }
                                    break;
                                }
                            }
                        }
                        
                        // Extract Name from tokens after enrollment, stopping at purely numeric fields or "Semester"
                        List<String> nameParts = new ArrayList<>();
                        for (int i = enrollIdx + 1; i < tokens.length; i++) {
                            String t = tokens[i];
                            // Skip the division token if it is after the enrollment
                            if (t.matches("^[a-zA-Z]\\d{0,3}$") && t.substring(0, 1).toUpperCase().equals(division)) {
                                continue;
                            }
                            // Skip the roll number token if it is standalone
                            if (!rollNo.isEmpty() && t.equals(rollNo)) {
                                continue;
                            }
                            if (t.matches("\\d+") || t.toLowerCase().equals("semester") || t.toLowerCase().equals("sem")) {
                                break;
                            }
                            if (t.matches("^[a-zA-Z\\.\\-']+$") || t.matches("^[a-zA-Z]+$")) {
                                nameParts.add(t);
                            } else {
                                break;
                            }
                        }
                        name = String.join(" ", nameParts);
                        
                        // Fallback name extraction
                        if (name.isEmpty()) {
                            List<String> fallbackParts = new ArrayList<>();
                            for (int i = enrollIdx + 1; i < tokens.length; i++) {
                                String t = tokens[i];
                                if (t.matches("^[a-zA-Z]\\d{0,3}$") && t.substring(0, 1).toUpperCase().equals(division)) {
                                    continue;
                                }
                                if (!rollNo.isEmpty() && t.equals(rollNo)) {
                                    continue;
                                }
                                if (t.matches("\\d+")) break;
                                fallbackParts.add(t);
                            }
                            name = String.join(" ", fallbackParts);
                        }
                    } else {
                        // Layout 1: Name is before enrollment.
                        List<String> nameParts = new ArrayList<>();
                        int start = (tokens[0].matches("\\d+")) ? 1 : 0;
                        for (int i = start; i < enrollIdx; i++) {
                            nameParts.add(tokens[i]);
                        }
                        name = String.join(" ", nameParts);
                        
                        // Division is after enrollment
                        for (int i = enrollIdx + 1; i < tokens.length; i++) {
                            String t = tokens[i];
                            if (t.matches("^[a-zA-Z]\\d{0,3}$")) {
                                division = t.substring(0, 1).toUpperCase();
                                if (t.length() > 1) {
                                    rollNo = t.substring(1);
                                } else if (i + 1 < tokens.length && tokens[i + 1].matches("\\d+")) {
                                    rollNo = tokens[i + 1];
                                }
                                break;
                            }
                        }
                    }
                    
                    // Find semester: search for "semester" or "sem"
                    for (int i = 0; i < tokens.length; i++) {
                        String t = tokens[i].toLowerCase();
                        if ((t.equals("semester") || t.equals("sem")) && i + 1 < tokens.length) {
                            semester = tokens[i + 1];
                            break;
                        }
                    }
                    
                    String finalDiv = division.isEmpty() ? "A" : division;
                    ParsedStudent student = new ParsedStudent();
                    student.setEnrollmentNo(enrollmentNo);
                    student.setStudentName(name.trim());
                    student.setDivision(finalDiv);
                    student.setSemester(semester.isEmpty() ? "3" : semester);
                    student.setRollNo(formatRollNo(finalDiv, rollNo));
                    
                    list.add(student);
                }
            }
        } finally {
            if (document != null) {
                document.close();
            }
        }
        return list;
    }

    private List<ParsedStudent> parseExcel(InputStream inputStream) throws Exception {
        List<ParsedStudent> list = new ArrayList<>();
        Workbook workbook = null;
        try {
            workbook = WorkbookFactory.create(inputStream);
            Sheet sheet = workbook.getSheetAt(0);
            
            int enrollCol = -1;
            int nameCol = -1;
            int divCol = -1;
            int semCol = -1;
            int rollCol = -1;
            
            // Auto detect column headers by reading the first row
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                    Cell cell = headerRow.getCell(i);
                    if (cell == null) continue;
                    String val = getCellValueAsString(cell).trim().toLowerCase();
                    if (val.contains("enroll")) {
                        enrollCol = i;
                    } else if (val.contains("roll") || val.equals("no") || val.equals("rollno")) {
                        rollCol = i;
                    } else if (val.contains("name") || val.contains("student")) {
                        nameCol = i;
                    } else if (val.contains("div")) {
                        divCol = i;
                    } else if (val.contains("sem")) {
                        semCol = i;
                    }
                }
            }
            
            // If no headers detected, assume defaults: col 0 is enrollment, col 1 is name, col 2 is division, col 3 is semester
            if (enrollCol == -1) enrollCol = 0;
            if (nameCol == -1) nameCol = 1;
            if (divCol == -1) divCol = 2;
            if (semCol == -1) semCol = 3;
            if (rollCol == -1) rollCol = 4;
            
            int startRow = (headerRow != null && (headerRow.getLastCellNum() > 0)) ? 1 : 0;
            for (int r = startRow; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                
                Cell enrollCell = row.getCell(enrollCol);
                Cell nameCell = row.getCell(nameCol);
                Cell divCell = row.getCell(divCol);
                Cell semCell = row.getCell(semCol);
                Cell rollCell = row.getCell(rollCol);
                
                String enroll = getCellValueAsString(enrollCell).trim();
                String name = getCellValueAsString(nameCell).trim();
                String division = getCellValueAsString(divCell).trim();
                String semester = getCellValueAsString(semCell).trim();
                String roll = getCellValueAsString(rollCell).trim();
                
                // Clean up enrollment: excel sometimes parses as double e.g. 2.40045e+11
                if (enroll.contains(".") && enroll.toLowerCase().contains("e")) {
                    try {
                        double d = Double.parseDouble(enroll);
                        enroll = String.format("%.0f", d);
                    } catch (Exception ignored) {}
                }
                
                if (enroll.isEmpty()) continue;
                
                String finalDiv = division.isEmpty() ? "A" : division;
                ParsedStudent student = new ParsedStudent();
                student.setEnrollmentNo(enroll);
                student.setStudentName(name.isEmpty() ? "Unknown" : name);
                student.setDivision(finalDiv);
                student.setSemester(semester.isEmpty() ? "3" : semester);
                student.setRollNo(formatRollNo(finalDiv, roll));
                list.add(student);
            }
        } finally {
            if (workbook != null) {
                workbook.close();
            }
        }
        return list;
    }

    private List<ParsedStudent> parseCsv(InputStream inputStream) throws Exception {
        List<ParsedStudent> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            boolean isFirst = true;
            int enrollCol = -1, nameCol = -1, divCol = -1, semCol = -1, rollCol = -1;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                // Split by comma ignoring commas inside quotes
                String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                
                if (isFirst) {
                    isFirst = false;
                    // Detect headers
                    for (int i = 0; i < parts.length; i++) {
                        String val = parts[i].replace("\"", "").trim().toLowerCase();
                        if (val.contains("enroll")) {
                            enrollCol = i;
                        } else if (val.contains("roll") || val.equals("no") || val.equals("rollno")) {
                            rollCol = i;
                        } else if (val.contains("name") || val.contains("student")) {
                            nameCol = i;
                        } else if (val.contains("div")) {
                            divCol = i;
                        } else if (val.contains("sem")) {
                            semCol = i;
                        }
                    }
                    
                    // If no headers, treat it as data and assume defaults
                    if (enrollCol == -1) {
                        enrollCol = 0;
                        nameCol = 1;
                        divCol = 2;
                        semCol = 3;
                        rollCol = 4;
                        // Since this line wasn't header, parse it as student row
                        parseCsvRow(parts, enrollCol, nameCol, divCol, semCol, rollCol, list);
                    }
                    continue;
                }
                
                parseCsvRow(parts, enrollCol, nameCol, divCol, semCol, rollCol, list);
            }
        }
        return list;
    }

    private void parseCsvRow(String[] parts, int enrollCol, int nameCol, int divCol, int semCol, int rollCol, List<ParsedStudent> list) {
        String enroll = getSafeArrayElement(parts, enrollCol).replace("\"", "").trim();
        String name = getSafeArrayElement(parts, nameCol).replace("\"", "").trim();
        String division = getSafeArrayElement(parts, divCol).replace("\"", "").trim();
        String semester = getSafeArrayElement(parts, semCol).replace("\"", "").trim();
        String roll = getSafeArrayElement(parts, rollCol).replace("\"", "").trim();
        
        if (enroll.isEmpty()) return;
        
        String finalDiv = division.isEmpty() ? "A" : division;
        ParsedStudent student = new ParsedStudent();
        student.setEnrollmentNo(enroll);
        student.setStudentName(name.isEmpty() ? "Unknown" : name);
        student.setDivision(finalDiv);
        student.setSemester(semester.isEmpty() ? "3" : semester);
        student.setRollNo(formatRollNo(finalDiv, roll));
        list.add(student);
    }

    private String getSafeArrayElement(String[] arr, int idx) {
        if (idx >= 0 && idx < arr.length) {
            return arr[idx];
        }
        return "";
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == (long) val) {
                    return String.valueOf((long) val);
                }
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        double numVal = cell.getNumericCellValue();
                        if (numVal == (long) numVal) {
                            return String.valueOf((long) numVal);
                        }
                        return String.valueOf(numVal);
                    } catch (Exception ex) {
                        return "";
                    }
                }
            default:
                return "";
        }
    }

    public static String formatRollNo(String division, String rollNo) {
        if (division == null || division.trim().isEmpty()) {
            return rollNo != null ? rollNo.trim() : "";
        }
        String cleanDiv = division.trim().toUpperCase();
        if (rollNo == null || rollNo.trim().isEmpty()) {
            return "";
        }
        String cleanRoll = rollNo.trim();
        if (cleanRoll.toUpperCase().startsWith(cleanDiv)) {
            cleanRoll = cleanRoll.substring(cleanDiv.length());
        }
        Pattern numericPattern = Pattern.compile("\\d+");
        Matcher matcher = numericPattern.matcher(cleanRoll);
        if (matcher.find()) {
            String digits = matcher.group();
            if (digits.length() == 1) {
                digits = "0" + digits;
            }
            return cleanDiv + digits;
        }
        return cleanDiv + cleanRoll;
    }
}
