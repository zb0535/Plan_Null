import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 로컬 파일 시스템과의 입출력을 전담하는 클래스
 *
 * <p>일정은 CSV, 태그는 텍스트 파일로 저장한다. 파일은 UTF-8 로 쓰고,
 * 옛 버전이 Windows 기본 인코딩(MS949)으로 저장한 파일도 읽을 수 있다.</p>
 * <pre>
 * schedule_data.csv : id,날짜,태그,내용,제외여부,완료여부,알림(시간 전),알림울림여부   (6열짜리 옛 파일도 읽음)
 * schedule_tags.txt : 태그이름=항목1,항목2,항목3
 * </pre>
 *
 * @author 정현우
 * @version 1.0
 */
public class FileManager {
    private final String filePath;
    private final String tagFilePath;

    public FileManager() {
        this("schedule_data.csv", "schedule_tags.txt");
    }

    public FileManager(String filePath, String tagFilePath) {
        this.filePath = filePath;
        this.tagFilePath = tagFilePath;
    }

    // ===== 일정 (CSV) =====

    public List<Schedule> readInitialData() {
        List<Schedule> list = new ArrayList<>();
        String text = readText(filePath);
        if (text == null) {
            return list;     // 최초 실행 시 파일이 없는 것은 정상
        }
        for (List<String> row : parseCsv(text)) {
            if (row.size() != 6 && row.size() != 8) {
                continue;    // 형식이 맞지 않는 줄은 건너뜀
            }
            int alertHours = row.size() == 8 ? parseInt(row.get(6)) : 0;
            boolean alerted = row.size() == 8 && Boolean.parseBoolean(row.get(7).trim());
            list.add(new Schedule(row.get(0), row.get(1), row.get(2), row.get(3),
                    Boolean.parseBoolean(row.get(4).trim()), Boolean.parseBoolean(row.get(5).trim()),
                    alertHours, alerted));
        }
        return list;
    }

    public boolean saveToCSV(List<Schedule> scheduleList) {
        StringBuilder sb = new StringBuilder("\uFEFF");   // 엑셀에서 한글이 깨지지 않도록 BOM 을 붙인다
        for (Schedule s : scheduleList) {
            sb.append(csv(s.getScheduleID())).append(',').append(csv(s.getTargetDate())).append(',')
              .append(csv(s.getTag())).append(',').append(csv(s.getContent())).append(',')
              .append(s.isExcluded()).append(',').append(s.isCompleted()).append(',')
              .append(s.getAlertHours()).append(',').append(s.isAlerted()).append("\r\n");
        }
        return writeText(filePath, sb.toString());
    }

    // ===== 태그 (텍스트) =====

    /** 저장된 태그(이름 -> 입력 항목들). 파일이 없으면 빈 Map */
    public Map<String, List<String>> readTags() {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        String text = readText(tagFilePath);
        if (text == null) {
            return tags;
        }
        for (String line : text.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            List<String> fields = new ArrayList<>();
            for (String field : line.substring(eq + 1).split(",")) {
                if (!field.trim().isEmpty()) {
                    fields.add(field.trim());
                }
            }
            if (!fields.isEmpty()) {
                tags.put(line.substring(0, eq).trim(), fields);
            }
        }
        return tags;
    }

    public boolean saveTags(Map<String, List<String>> tags) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : tags.entrySet()) {
            sb.append(e.getKey()).append('=').append(String.join(",", e.getValue())).append('\n');
        }
        return writeText(tagFilePath, sb.toString());
    }

    // ===== 내부 =====

    private static int parseInt(String text) {
        try {
            return Math.max(0, Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 파일 전체를 문자열로 읽는다. UTF-8 이 아니면 MS949 로 다시 해석한다. 파일이 없거나 못 읽으면 null */
    private static String readText(String path) {
        Path file = Paths.get(path);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                Charset legacy = Charset.isSupported("MS949") ? Charset.forName("MS949") : Charset.defaultCharset();
                text = new String(bytes, legacy);
            }
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException e) {
            return null;
        }
    }

    /** 임시 파일에 먼저 쓴 뒤 바꿔치기해서, 저장 도중 문제가 생겨도 기존 파일이 깨지지 않게 한다. */
    private static boolean writeText(String path, String text) {
        Path target = Paths.get(path).toAbsolutePath();
        Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try {
            Files.write(temp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 임시 파일 정리 실패는 무시
            }
            return false;   // 다른 프로그램(예: 엑셀)이 파일을 열어 두면 여기로 온다
        }
    }

    private static String csv(String value) {
        String v = value == null ? "" : value;
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** 따옴표·줄바꿈이 들어간 칸까지 처리하는 CSV 해석기 */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);           // 빈 줄은 건너뜀
                }
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}