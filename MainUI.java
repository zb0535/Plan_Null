import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.font.TextAttribute;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 사용자에게 보여지는 주 화면(화면 A)을 구성하는 JFrame 클래스
 *
 * <ul>
 *   <li>상단: 전체 일정 대비 완료 비율(%) + 막대그래프</li>
 *   <li>중앙: D-Day 순 일정 목록 또는 월별 달력 (상단 버튼으로 전환)</li>
 *   <li>하단: [일정 추가] [일정 변경] [일정 삭제] (삭제 전에는 확인 팝업)</li>
 *   <li>알림: 30초마다 알림 시각이 된 일정을 확인해 소리와 팝업으로 알려 준다 (프로그램이 켜져 있는 동안)</li>
 * </ul>
 *
 * @author 정현우
 * @version 1.0
 */
public class MainUI extends JFrame {
    private static final Color PEACH = new Color(248, 203, 173);
    private static final Color PEACH_LIGHT = new Color(251, 229, 214);
    private static final Color PEACH_SELECTED = new Color(244, 176, 132);
    private static final Color NAVY = new Color(44, 62, 94);
    private static final int CHECK_AREA_WIDTH = 46;         // 행 왼쪽 이 폭 안을 누르면 '완료 체크' 로 본다
    private static final int REMINDER_CHECK_MS = 30_000;    // 알림 시각을 확인하는 주기

    private final ScheduleManager manager;
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel percentLabel = new JLabel("0%");
    private final DefaultListModel<Schedule> listModel = new DefaultListModel<>();
    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerPanel = new JPanel(centerCards);
    private final AtomicBoolean saveErrorShown = new AtomicBoolean(false);
    private final JPanel calendarPanel = new JPanel(new BorderLayout(0, 6));
    private final JLabel monthLabel = new JLabel("", SwingConstants.CENTER);
    private YearMonth displayedMonth = YearMonth.now();
    private boolean calendarView;
    private JList<Schedule> scheduleList;
    private javax.swing.Timer reminderTimer;

    public MainUI() {
        this(new ScheduleManager());
    }

    public MainUI(ScheduleManager manager) {
        this.manager = manager;
        setTitle("Plan:Null - Personal Schedule Manager");
        setSize(780, 540);
        setMinimumSize(new Dimension(640, 420));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);      // 닫을 때 저장 대기 작업을 마무리해야 하므로 직접 처리
        setLocationRelativeTo(null);                        // 화면 중앙 배치
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApp();
            }
        });
        manager.setSaveFailureListener(this::showSaveError);

        // 1. 상단: 달성률(%) + 막대그래프
        percentLabel.setFont(percentLabel.getFont().deriveFont(Font.BOLD, 26f));
        progressBar.setForeground(PEACH);
        progressBar.setBackground(PEACH_LIGHT);
        progressBar.setBorder(new LineBorder(NAVY, 2, true));
        progressBar.setPreferredSize(new Dimension(100, 32));
        JPanel topPanel = new JPanel(new BorderLayout(14, 0));
        topPanel.setBorder(new EmptyBorder(14, 20, 6, 20));
        topPanel.add(percentLabel, BorderLayout.WEST);
        topPanel.add(progressBar, BorderLayout.CENTER);
        JToggleButton listViewButton = new JToggleButton("목록");
        JToggleButton calendarViewButton = new JToggleButton("달력");
        ButtonGroup viewGroup = new ButtonGroup();
        viewGroup.add(listViewButton);
        viewGroup.add(calendarViewButton);
        listViewButton.setSelected(true);
        listViewButton.addActionListener(e -> {
            calendarView = false;
            centerCards.show(centerPanel, listModel.isEmpty() ? "empty" : "list");
        });
        calendarViewButton.addActionListener(e -> {
            calendarView = true;
            refreshCalendar();
            centerCards.show(centerPanel, "calendar");
        });
        JPanel viewButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        viewButtons.add(listViewButton);
        viewButtons.add(calendarViewButton);
        topPanel.add(viewButtons, BorderLayout.EAST);

        // 2. 중앙: 일정 목록 (D-Day 순)
        scheduleList = new JList<Schedule>(listModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                Schedule s = scheduleAt(e);
                return s == null ? null : tooltipFor(s);
            }
        };
        scheduleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        scheduleList.setCellRenderer(new RowRenderer());
        scheduleList.setBackground(new Color(238, 238, 238));
        scheduleList.setToolTipText("");                    // 행마다 다른 툴팁을 쓰기 위해 툴팁 기능만 켜 둔다
        scheduleList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Schedule hit = scheduleAt(e);
                if (hit != null && SwingUtilities.isLeftMouseButton(e) && inCheckArea(e)) {
                    toggleCompletion(hit);                  // 3. 왼쪽 체크박스: 완료 토글 + 진행률 즉시 갱신
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2
                        && scheduleAt(e) != null && !inCheckArea(e)) {
                    editSelected();                         // 더블클릭: 일정 변경
                }
            }
        });
        bindKeys();

        JScrollPane scroll = new JScrollPane(scheduleList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(new LineBorder(NAVY, 2));
        JLabel emptyLabel = new JLabel("등록된 일정이 없습니다. 아래 [일정 추가]를 눌러 시작해 보세요.", SwingConstants.CENTER);
        emptyLabel.setOpaque(true);
        emptyLabel.setBackground(scheduleList.getBackground());
        emptyLabel.setBorder(new LineBorder(NAVY, 2));
        centerPanel.setBorder(new EmptyBorder(8, 20, 8, 20));
        centerPanel.add(scroll, "list");
        centerPanel.add(emptyLabel, "empty");
        centerPanel.add(createCalendarPanel(), "calendar");

        // 3. 하단 버튼 (4: 추가, 5: 변경·삭제)
        JButton addBtn = styledButton("일정 추가");
        JButton editBtn = styledButton("일정 변경");
        JButton delBtn = styledButton("일정 삭제");
        addBtn.addActionListener(e -> openPopup(null));
        editBtn.addActionListener(e -> editSelected());
        delBtn.addActionListener(e -> deleteSelected());
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 46, 14));
        bottomPanel.add(addBtn);
        bottomPanel.add(editBtn);
        bottomPanel.add(delBtn);

        add(topPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        refreshListStatus();                                // 초기 데이터 로드

        // 알림: 30초마다 알림 시각이 된 일정이 있는지 확인
        reminderTimer = new javax.swing.Timer(REMINDER_CHECK_MS, e -> checkReminders());
        reminderTimer.setInitialDelay(1000);
        reminderTimer.start();
    }

    public void displayScreen() {
        setVisible(true);
    }

    /** 일정 목록과 진행률(1번)을 다시 그린다. 선택해 둔 일정은 그대로 선택 상태를 유지한다. */
    public void refreshListStatus() {
        Schedule selected = scheduleList.getSelectedValue();
        String selectedId = selected == null ? null : selected.getScheduleID();
        listModel.clear();
        int reselect = -1;
        for (Schedule s : manager.sortSchedulesByDDay()) {
            if (s.getScheduleID().equals(selectedId)) {
                reselect = listModel.size();
            }
            listModel.addElement(s);
        }
        if (reselect >= 0) {
            scheduleList.setSelectedIndex(reselect);
        }
        int progress = manager.calculateProgressRate();
        progressBar.setValue(progress);
        percentLabel.setText(progress + "%");
        if (calendarView) {
            refreshCalendar();
            centerCards.show(centerPanel, "calendar");
        } else {
            centerCards.show(centerPanel, listModel.isEmpty() ? "empty" : "list");
        }
    }

    private JPanel createCalendarPanel() {
        JPanel header = new JPanel(new BorderLayout());
        JButton previous = styledButton("<");
        JButton next = styledButton(">");
        previous.addActionListener(e -> {
            displayedMonth = displayedMonth.minusMonths(1);
            refreshCalendar();
        });
        next.addActionListener(e -> {
            displayedMonth = displayedMonth.plusMonths(1);
            refreshCalendar();
        });
        monthLabel.setFont(monthLabel.getFont().deriveFont(Font.BOLD, 18f));
        header.add(previous, BorderLayout.WEST);
        header.add(monthLabel, BorderLayout.CENTER);
        header.add(next, BorderLayout.EAST);
        calendarPanel.add(header, BorderLayout.NORTH);
        refreshCalendar();
        return calendarPanel;
    }

    private void refreshCalendar() {
        if (calendarPanel.getComponentCount() > 1) {
            calendarPanel.remove(1);
        }
        monthLabel.setText(displayedMonth.format(DateTimeFormatter.ofPattern("yyyy년 M월")));
        JPanel grid = new JPanel(new GridLayout(7, 7, 3, 3));
        for (String day : new String[]{"월", "화", "수", "목", "금", "토", "일"}) {
            JLabel header = new JLabel(day, SwingConstants.CENTER);
            header.setFont(header.getFont().deriveFont(Font.BOLD));
            header.setForeground(NAVY);
            grid.add(header);
        }
        LocalDate first = displayedMonth.atDay(1);
        int leading = first.getDayOfWeek().getValue() - 1;
        List<Schedule> schedules = manager.sortSchedulesByDDay();
        for (int i = 0; i < 42; i++) {
            int dayNumber = i - leading + 1;
            JPanel dayCell = new JPanel();
            dayCell.setLayout(new BoxLayout(dayCell, BoxLayout.Y_AXIS));
            dayCell.setBorder(new LineBorder(NAVY, 1));
            dayCell.setBackground(Color.WHITE);
            if (dayNumber < 1 || dayNumber > displayedMonth.lengthOfMonth()) {
                dayCell.setBackground(new Color(238, 238, 238));
                grid.add(dayCell);
                continue;
            }
            LocalDate date = displayedMonth.atDay(dayNumber);
            JLabel dayLabel = new JLabel(String.valueOf(dayNumber));
            dayLabel.setBorder(new EmptyBorder(2, 4, 2, 2));
            dayCell.add(dayLabel);
            for (Schedule schedule : schedules) {
                if (!date.toString().equals(schedule.getTargetDate())) {
                    continue;
                }
                JLabel scheduleLabel = new JLabel((schedule.isCompleted() ? "✓ " : "") + schedule.getTitle());
                scheduleLabel.setToolTipText(tooltipFor(schedule));
                scheduleLabel.setFont(scheduleLabel.getFont().deriveFont(schedule.isCompleted() ? Font.PLAIN : Font.BOLD, 11f));
                scheduleLabel.setForeground(schedule.isCompleted() ? Color.GRAY : NAVY);
                scheduleLabel.setBorder(new EmptyBorder(1, 4, 1, 2));
                scheduleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                scheduleLabel.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                            selectSchedule(schedule);
                            editSelected();
                        }
                    }
                });
                dayCell.add(scheduleLabel);
            }
            grid.add(dayCell);
        }
        calendarPanel.add(grid, BorderLayout.CENTER);
        calendarPanel.revalidate();
        calendarPanel.repaint();
    }

    private void selectSchedule(Schedule schedule) {
        int index = listModel.indexOf(schedule);
        if (index >= 0) {
            scheduleList.setSelectedIndex(index);
        }
    }

    // ===== 동작 =====

    private void openPopup(Schedule target) {
        new PopupUI(this, manager, target).showPopup();      // 화면 중앙에 모달 팝업(화면 B)
    }

    private void editSelected() {
        Schedule s = scheduleList.getSelectedValue();
        if (s == null) {
            JOptionPane.showMessageDialog(this, "변경할 일정을 먼저 선택하세요.");
            return;
        }
        openPopup(s);
    }

    private void deleteSelected() {
        Schedule s = scheduleList.getSelectedValue();
        if (s == null) {
            JOptionPane.showMessageDialog(this, "삭제할 일정을 먼저 선택하세요.");
            return;
        }
        int answer = JOptionPane.showOptionDialog(this,
                "'" + s.getTitle() + "' 일정을 삭제할까요?\n삭제한 일정은 되돌릴 수 없어요.", "삭제 확인",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                new Object[]{"삭제", "취소"}, "취소");
        if (answer == 0) {
            manager.deleteSchedule(s.getScheduleID());
            refreshListStatus();
        }
    }

    private void toggleCompletion(Schedule s) {
        manager.updateCompletionStatus(s.getScheduleID(), !s.isCompleted());
        refreshListStatus();
    }

    private void bindKeys() {
        InputMap in = scheduleList.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap act = scheduleList.getActionMap();
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "toggleDone");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "editItem");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteItem");
        act.put("toggleDone", action(() -> {
            Schedule s = scheduleList.getSelectedValue();
            if (s != null) {
                toggleCompletion(s);
            }
        }));
        act.put("editItem", action(this::editSelected));
        act.put("deleteItem", action(this::deleteSelected));
    }

    private Schedule scheduleAt(MouseEvent e) {
        int i = scheduleList.locationToIndex(e.getPoint());
        Rectangle b = i < 0 ? null : scheduleList.getCellBounds(i, i);
        return (b != null && b.contains(e.getPoint())) ? listModel.get(i) : null;
    }

    private boolean inCheckArea(MouseEvent e) {
        int i = scheduleList.locationToIndex(e.getPoint());
        Rectangle b = i < 0 ? null : scheduleList.getCellBounds(i, i);
        return b != null && e.getX() - b.x < CHECK_AREA_WIDTH;
    }

    private void showSaveError(String message) {
        if (saveErrorShown.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(this, message, "저장 실패", JOptionPane.ERROR_MESSAGE);
                saveErrorShown.set(false);
            });
        }
    }

    private void exitApp() {
        reminderTimer.stop();
        manager.shutdown();                                  // 저장 대기 중인 작업을 마무리
        dispose();
        System.exit(0);
    }

    // ===== 알림 =====

    /** 알림 시각이 된 일정이 있으면 소리와 팝업으로 알린다. */
    private void checkReminders() {
        List<Schedule> due = manager.collectDueAlerts(LocalDateTime.now());
        if (!due.isEmpty()) {
            refreshListStatus();
            showReminder(due);
        }
    }

    private void showReminder(List<Schedule> due) {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        for (Schedule s : due) {
            long minutes = Math.max(0, Duration.between(now, s.getEventDateTime()).toMinutes());
            sb.append("• [").append(s.getTag()).append("] ").append(s.getTitle()).append('\n')
              .append("    ").append(s.getTargetDate()).append(' ').append(s.getEventDateTime().toLocalTime())
              .append("  (").append(remainingText(minutes)).append(" 남음)\n");
        }
        playChime();

        JLabel head = new JLabel("곧 시작하는 일정이 있어요!");
        head.setFont(head.getFont().deriveFont(Font.BOLD, 17f));
        JTextArea body = new JTextArea(sb.toString().trim());
        body.setEditable(false);
        body.setOpaque(false);
        JButton ok = new JButton("확인");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER));
        south.add(ok);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(18, 22, 12, 22));
        content.add(head, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        // 주 창이 최소화돼 있어도 보이도록 소유자 없이, 항상 위에 뜨는 창으로 만든다
        JDialog dialog = new JDialog((Frame) null, "Plan:Null - 일정 알림", false);
        dialog.setAlwaysOnTop(true);
        dialog.setContentPane(content);
        ok.addActionListener(e -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(ok);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        dialog.toFront();
    }

    private static String remainingText(long minutes) {
        long h = minutes / 60;
        long m = minutes % 60;
        if (h == 0) {
            return m == 0 ? "1분 미만" : m + "분";
        }
        return h + "시간" + (m == 0 ? "" : " " + m + "분");
    }

    /** 알림음(띠리링). 소리 장치를 못 쓰면 기본 경고음으로 대신한다. */
    private static void playChime() {
        Thread t = new Thread(() -> {
            try {
                float rate = 44100f;
                int[][] notes = {{880, 170}, {1175, 170}, {1568, 420}};      // {주파수(Hz), 길이(ms)}
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                for (int[] n : notes) {
                    int count = (int) (rate * n[1] / 1000);
                    for (int i = 0; i < count; i++) {
                        double fade = Math.pow(1.0 - (double) i / count, 1.5);   // 서서히 작아지는 소리
                        short v = (short) (Math.sin(2 * Math.PI * n[0] * i / rate) * fade * 0.4 * Short.MAX_VALUE);
                        pcm.write(v & 0xFF);
                        pcm.write((v >> 8) & 0xFF);
                    }
                }
                byte[] data = pcm.toByteArray();
                AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                    line.open(format);
                    line.start();
                    line.write(data, 0, data.length);
                    line.drain();
                }
            } catch (Exception e) {
                Toolkit.getDefaultToolkit().beep();
            }
        }, "reminder-sound");
        t.setDaemon(true);
        t.start();
    }

    // ===== 목록 모양 =====

    private static String dDayText(int dDay) {
        return dDay == 0 ? "D-Day" : (dDay > 0 ? "D-" + dDay : "D+" + Math.abs(dDay));
    }

    /** "[태그] 제목    항목: 값    항목: 값" */
    private static String summary(Schedule s) {
        StringBuilder sb = new StringBuilder("[").append(s.getTag()).append("] ").append(s.getTitle());
        for (Map.Entry<String, String> e : s.getDetails().entrySet()) {
            sb.append("    ").append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    private static String alertText(Schedule s) {
        if (s.getAlertHours() <= 0) {
            return "";
        }
        return "알림 " + s.getAlertHours() + "시간 전" + (s.isAlerted() ? " (울림)" : "");
    }

    private static String tooltipFor(Schedule s) {
        StringBuilder sb = new StringBuilder("<html><b>").append(esc(s.getTitle())).append("</b><br>[")
                .append(esc(s.getTag())).append("]  목표 날짜 ").append(s.getTargetDate());
        for (Map.Entry<String, String> e : s.getDetails().entrySet()) {
            sb.append("<br>").append(esc(e.getKey())).append(": ").append(esc(e.getValue()));
        }
        if (s.getAlertHours() > 0) {
            sb.append("<br>알림: 일정 ").append(s.getAlertHours()).append("시간 전").append(s.isAlerted() ? " (이미 울렸어요)" : "");
        }
        return sb.append("</html>").toString();
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static Font strike(Font base, boolean on) {
        Map<TextAttribute, Object> attrs = new HashMap<>(base.getAttributes());
        attrs.put(TextAttribute.STRIKETHROUGH, on ? TextAttribute.STRIKETHROUGH_ON : Boolean.FALSE);
        return base.deriveFont(attrs);
    }

    private static JButton styledButton(String text) {
        JButton b = new JButton(text);
        b.setBackground(PEACH_LIGHT);
        b.setFocusPainted(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 15f));
        b.setBorder(new CompoundBorder(new LineBorder(NAVY, 2, true), new EmptyBorder(8, 24, 8, 24)));
        return b;
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private class RowRenderer extends JPanel implements ListCellRenderer<Schedule> {
        private final JPanel card = new JPanel(new BorderLayout(10, 0));
        private final JCheckBox check = new JCheckBox();
        private final JLabel dday = new JLabel();
        private final JLabel text = new JLabel();
        private final JLabel alert = new JLabel();
        private final Font normalFont;
        private final Font boldFont;

        RowRenderer() {
            super(new BorderLayout());
            normalFont = text.getFont().deriveFont(Font.PLAIN);
            boldFont = text.getFont().deriveFont(Font.BOLD);
            alert.setFont(normalFont.deriveFont(12f));
            alert.setForeground(new Color(150, 70, 20));
            check.setOpaque(false);
            dday.setPreferredSize(new Dimension(62, 22));
            JPanel left = new JPanel(new BorderLayout(4, 0));
            left.setOpaque(false);
            left.add(check, BorderLayout.WEST);
            left.add(dday, BorderLayout.CENTER);
            card.setBorder(new CompoundBorder(new LineBorder(NAVY, 1, true), new EmptyBorder(7, 8, 7, 12)));
            card.add(left, BorderLayout.WEST);
            card.add(text, BorderLayout.CENTER);
            card.add(alert, BorderLayout.EAST);
            setBorder(new EmptyBorder(3, 8, 3, 8));
            add(card, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, super.getPreferredSize().height);   
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Schedule> list, Schedule s, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            boolean done = s.isCompleted();
            Color fg = done ? Color.GRAY : Color.BLACK;
            check.setSelected(done);
            dday.setText(dDayText(s.calculateDDay()));
            dday.setFont(strike(boldFont, done));
            dday.setForeground(fg);
            text.setText(summary(s));
            text.setFont(strike(normalFont, done));
            text.setForeground(fg);
            alert.setText(alertText(s));
            card.setBackground(isSelected ? PEACH_SELECTED : PEACH_LIGHT);
            setBackground(list.getBackground());
            return this;
        }
    }

    private static void applyUIFont() {
        Set<String> installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        String family = Font.DIALOG;
        for (String name : new String[]{"맑은 고딕", "Malgun Gothic", "Apple SD Gothic Neo", "NanumGothic", "나눔고딕", "Noto Sans CJK KR"}) {
            if (installed.contains(name)) {
                family = name;
                break;
            }
        }
        FontUIResource font = new FontUIResource(family, Font.PLAIN, 14);
        for (Object key : Collections.list(UIManager.getDefaults().keys())) {
            if (UIManager.get(key) instanceof FontUIResource) {
                UIManager.put(key, font);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            applyUIFont();
            new MainUI().displayScreen();
        });
    }
}