package mx.salter.api.recipient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.salter.api.common.ApiException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Lee CSV, XLSX o XLS en una tabla de texto (encabezados + filas). Valida tipo real por firma del archivo. */
public final class SpreadsheetReader {
    private SpreadsheetReader() {}

    public record Table(List<String> headers, List<List<String>> rows) {}

    public static Table read(String filename, byte[] data, int maxRows) {
        if (data == null || data.length == 0) throw ApiException.badRequest("EMPTY_FILE", "El archivo está vacío.");
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4;
        boolean ole = data.length > 8 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF && (data[2] & 0xFF) == 0x11 && (data[3] & 0xFF) == 0xE0;
        Table t;
        if (name.endsWith(".xlsx") && zip || name.endsWith(".xls") && ole) {
            t = readWorkbook(data, maxRows);
        } else if (name.endsWith(".csv") || name.endsWith(".txt")) {
            if (zip || ole) throw ApiException.badRequest("INVALID_FILE", "El archivo no es un CSV válido.");
            t = readCsv(data, maxRows);
        } else {
            throw ApiException.badRequest("INVALID_FILE", "Formato no admitido. Usa Excel (.xlsx, .xls) o CSV.");
        }
        if (t.headers().isEmpty()) throw ApiException.badRequest("NO_HEADERS", "No se encontraron encabezados en la primera fila.");
        return t;
    }

    private static Table readWorkbook(byte[] data, int maxRows) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.forLanguageTag("es-MX"));
            List<String> headers = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            boolean headerRead = false;
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                int last = Math.max(row.getLastCellNum(), headers.size());
                for (int i = 0; i < last; i++) {
                    Cell c = row.getCell(i);
                    values.add(c == null ? "" : fmt.formatCellValue(c).trim());
                }
                if (values.stream().allMatch(String::isBlank)) continue;
                if (!headerRead) {
                    headers.addAll(values);
                    headerRead = true;
                } else {
                    rows.add(values);
                    if (rows.size() > maxRows) throw tooMany(maxRows);
                }
            }
            return new Table(cleanHeaders(headers), rows);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException ae) throw ae;
            throw ApiException.badRequest("INVALID_FILE", "No se pudo leer el archivo de Excel.");
        }
    }

    private static Table readCsv(byte[] data, int maxRows) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.contains("�")) text = new String(data, java.nio.charset.Charset.forName("windows-1252"));
        if (text.startsWith("﻿")) text = text.substring(1);
        String firstLine = text.lines().findFirst().orElse("");
        char sep = count(firstLine, ';') > count(firstLine, ',') ? ';' : (count(firstLine, '\t') > count(firstLine, ',') ? '\t' : ',');
        List<List<String>> all = parseCsv(text, sep);
        all.removeIf(r -> r.stream().allMatch(String::isBlank));
        if (all.isEmpty()) return new Table(List.of(), List.of());
        List<String> headers = cleanHeaders(all.get(0));
        List<List<String>> rows = all.subList(1, all.size());
        if (rows.size() > maxRows) throw tooMany(maxRows);
        return new Table(headers, new ArrayList<>(rows));
    }

    static List<List<String>> parseCsv(String text, char sep) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == sep) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    private static List<String> cleanHeaders(List<String> raw) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i) == null || raw.get(i).isBlank() ? "Columna " + (i + 1) : raw.get(i).trim();
            int n = seen.merge(h, 1, Integer::sum);
            out.add(n > 1 ? h + " (" + n + ")" : h);
        }
        return out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char x : s.toCharArray()) if (x == c) n++;
        return n;
    }

    private static ApiException tooMany(int max) {
        return ApiException.badRequest("TOO_MANY_ROWS", "El archivo excede el máximo de " + max + " filas.");
    }
}
