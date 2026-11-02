import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.font.TextAttribute;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 일정 추가·변경 시 호출되는 팝업 창(화면 B)을 구성하는 JDialog 클래스
 *
 * <ul>
 *   <li>태그를 고르면 그 태그의 입력 항목이 동적으로 나타난다. (태그는 [태그 관리] 에서 직접 만들 수 있다)</li>
 *   <li>항목 왼쪽 체크박스를 끄면 그 항목은 일정에서 제외된다. (첫 항목 = 제목은 필수)</li>
 *   <li>'알림' 을 켜면 "시간" 항목의 시각보다 N시간 전에 알림이 울린다.</li>
 *   <li>[저장] 은 입력값을 ScheduleManager 로 넘겨 백그라운드 자동 저장을 일으키고 창을 닫는다.</li>
 * </ul>
 *
 * @author 정현우
 * @version 1.0
 */
public class PopupUI extends JDialog {
    private final ScheduleManager manager;
    private final MainUI mainUI;
    private final Schedule editTarget;                     // null 이면 새 일정 추가, 아니면 그 일정을 변경

    private final JTextField dateInput = new HintTextField("YYYY.MM.DD", 10);
    private final JComboBox<String> tagSelect = new JComboBox<>();
    private final JPanel fieldPanel = new JPanel(new GridBagLayout());      // 태그에 따라 바뀌는 입력 항목들
    private final List<FieldRow> rows = new ArrayList<>();
    private final JCheckBox alertCheck = new JCheckBox("알림");
    private final JSpinner alertHours = new JSpinner(new SpinnerNumberModel(3, 1, 168, 1));
    private final JLabel alertHint = new JLabel(" ");
    private final Map<String, String> typedValues = new HashMap<>();        // 태그를 바꿔도 같은 이름의 항목 값은 유지
    private final Map<String, Boolean> usedFlags = new HashMap<>();
    private String titleText = "";                                          // 첫 번째 항목(제목)에 입력한 값
    private boolean adjusting;                                              // 콤보박스를 코드로 바꾸는 동안에는 화면을 다시 만들지 않는다

    public PopupUI(MainUI mainUI, ScheduleManager manager) {
        this(mainUI, manager, null);
    }

    /** @param editTarget 변경할 일정 (null 이면 새 일정 추가) */
    public PopupUI(MainUI mainUI, ScheduleManager manager, Schedule editTarget) {
        super(mainUI, editTarget == null ? "Plan:Null - 일정 추가" : "Plan:Null - 일정 변경", true);
        this.mainUI = mainUI;
        this.manager = manager;
        this.editTarget = editTarget;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // 6, 7. 목표 날짜 / 태그 선택 / 태그 관리
        JButton tagBtn = new JButton("태그 관리");
        tagBtn.addActionListener(e -> openTagDialog());
        tagSelect.addActionListener(e -> {
            if (!adjusting) {
                rebuildFields();
            }
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        top.setBorder(new EmptyBorder(4, 8, 0, 8));
        top.add(new JLabel("목표 날짜:"));
        top.add(dateInput);
        top.add(new JLabel("태그:"));
        top.add(tagSelect);
        top.add(tagBtn);

        // 알림 줄
        alertCheck.addActionListener(e -> updateAlertState());
        alertHint.setForeground(Color.GRAY);
        alertHint.setFont(alertHint.getFont().deriveFont(Font.PLAIN, 12f));
        alertHint.setBorder(new EmptyBorder(0, 8, 0, 0));
        JPanel alertRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        alertRow.add(alertCheck);
        alertRow.add(alertHours);
        alertRow.add(new JLabel("시간 전에 알려 주기"));
        JPanel alertPanel = new JPanel(new BorderLayout());
        alertPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        alertPanel.add(alertRow, BorderLayout.NORTH);
        alertPanel.add(alertHint, BorderLayout.CENTER);

        // 8. 태그에 따라 바뀌는 입력 항목 + 알림
        JPanel center = new JPanel(new BorderLayout());
        center.setBorder(new EmptyBorder(4, 14, 4, 14));
        center.add(fieldPanel, BorderLayout.NORTH);
        center.add(alertPanel, BorderLayout.CENTER);

        // 9. 저장 / 취소
        JButton saveBtn = new JButton("저장");
        JButton cancelBtn = new JButton("취소");
        saveBtn.addActionListener(e -> saveInputData());
        cancelBtn.addActionListener(e -> dispose());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 10));
        bottom.add(saveBtn);
        bottom.add(cancelBtn);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(saveBtn);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        if (editTarget != null) {                          // 변경 모드: 기존 값으로 채워 둔다
            dateInput.setText(editTarget.getTargetDate());
            titleText = editTarget.getTitle();
            typedValues.putAll(editTarget.getDetails());
            if (editTarget.getAlertHours() > 0) {
                alertCheck.setSelected(true);
                alertHours.setValue(Math.min(168, editTarget.getAlertHours()));
            }
        }
        loadTagNames(editTarget == null ? null : editTarget.getTag());
        rebuildFields();
        setLocationRelativeTo(mainUI);
    }

    public void showPopup() {
        setVisible(true);
    }

    /** [저장] : 입력값을 검사해 ScheduleManager 로 넘기고(→ 백그라운드 자동 저장) 창을 닫는다. */
    public void saveInputData() {
        captureInput();
        String date = Schedule.normalizeDate(dateInput.getText());
        if (date == null) {
            warn("목표 날짜를 'YYYY.MM.DD' 또는 'YYYY-MM-DD' 형식의 올바른 날짜로 입력해 주세요.");
            dateInput.requestFocus();
            return;
        }
        FieldRow first = rows.get(0);
        String title = first.input.getText().trim();
        if (title.isEmpty()) {
            warn("'" + first.key + "' 항목을 입력해 주세요.");
            first.input.requestFocus();
            return;
        }
        Map<String, String> details = new LinkedHashMap<>();
        boolean excluded = false;
        for (int i = 1; i < rows.size(); i++) {
            FieldRow row = rows.get(i);
            if (!row.use.isSelected()) {
                excluded = true;                           // 체크를 끈 항목은 일정에서 제외
            } else if (!row.input.getText().trim().isEmpty()) {
                details.put(row.key, row.input.getText().trim());
            }
        }

        int hours = 0;
        if (alertCheck.isEnabled() && alertCheck.isSelected()) {
            FieldRow timeRow = findRow(Schedule.TIME_KEY);
            LocalTime time = (timeRow != null && timeRow.use.isSelected())
                    ? Schedule.parseTime(timeRow.input.getText()) : null;
            if (time == null) {
                warn("알림을 쓰려면 '시간' 항목에 시각을 적어 주세요.\n예) 14:30,  09:00~10:30,  오후 2시");
                return;
            }
            if (!LocalDate.parse(date).atTime(time).isAfter(LocalDateTime.now())) {
                int answer = JOptionPane.showOptionDialog(this,
                        "일정 시각이 이미 지났어요. 이대로 저장하면 알림은 울리지 않아요.", "알림 확인",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                        new Object[]{"그래도 저장", "돌아가기"}, "돌아가기");
                if (answer != 0) {
                    return;
                }
            }
            hours = ((Number) alertHours.getValue()).intValue();
        }

        String tag = (String) tagSelect.getSelectedItem();
        String content = Schedule.buildContent(title, details);
        boolean saved = editTarget == null
                ? manager.addSchedule(date, tag, content, excluded, hours)
                : manager.updateSchedule(editTarget.getScheduleID(), date, tag, content, excluded, hours);
        if (!saved) {
            warn("일정을 저장하지 못했어요. 입력한 내용을 다시 확인해 주세요.");
            return;
        }
        mainUI.refreshListStatus();
        dispose();
    }

    // ===== 태그 · 입력 항목 =====

    /** 태그 목록을 다시 불러온다. 변경 중인 일정의 태그가 지워진 태그여도 그대로 보여 준다. */
    private void loadTagNames(String preferred) {
        List<String> names = manager.getTagNames();
        if (preferred != null && !names.contains(preferred)
                && editTarget != null && preferred.equals(editTarget.getTag())) {
            names.add(preferred);
        }
        if (names.isEmpty()) {
            names.add("기타");
        }
        adjusting = true;
        tagSelect.setModel(new DefaultComboBoxModel<>(names.toArray(new String[0])));
        tagSelect.setSelectedItem(preferred != null && names.contains(preferred) ? preferred : names.get(0));
        adjusting = false;
    }

    private void openTagDialog() {
        captureInput();
        Object current = tagSelect.getSelectedItem();
        new TagDialog(this, manager).showDialog();
        loadTagNames(current == null ? null : current.toString());
        rebuildFields();
    }

    /** 8. 선택한 태그의 입력 항목으로 폼을 다시 만든다. 이미 입력한 값은 같은 이름의 항목에 그대로 남는다. */
    private void rebuildFields() {
        captureInput();
        String tag = (String) tagSelect.getSelectedItem();
        List<String> keys = manager.getTagFields(tag);
        if (editTarget != null) {                          // 변경하는 일정에 이미 있던 항목은 태그를 바꿔도 남겨 둔다
            for (String key : editTarget.getDetails().keySet()) {
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        rows.clear();
        fieldPanel.removeAll();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            FieldRow row = new FieldRow(key, i == 0);
            String text = i == 0 ? titleText : typedValues.get(key);
            Boolean used = usedFlags.get(key);
            row.input.setText(text == null ? "" : text);
            row.use.setSelected(i == 0 || used == null || used);
            row.setUsed(row.use.isSelected());
            rows.add(row);
            row.addTo(fieldPanel, i);
        }
        boolean hasTime = findRow(Schedule.TIME_KEY) != null;
        alertCheck.setEnabled(hasTime);
        if (!hasTime) {
            alertCheck.setSelected(false);
        }
        updateAlertState();
        fieldPanel.revalidate();
        fieldPanel.repaint();
        pack();
        if (isShowing()) {
            setLocationRelativeTo(getOwner());
        }
    }

    /** 지금 화면에 입력된 값을 기억해 둔다. (태그를 바꿔 폼을 다시 만들어도 값이 사라지지 않게) */
    private void captureInput() {
        for (int i = 0; i < rows.size(); i++) {
            FieldRow row = rows.get(i);
            if (i == 0) {
                titleText = row.input.getText();
            } else {
                typedValues.put(row.key, row.input.getText());
            }
            usedFlags.put(row.key, row.use.isSelected());
        }
    }

    private void updateAlertState() {
        boolean usable = alertCheck.isEnabled();
        alertHours.setEnabled(usable && alertCheck.isSelected());
        alertHint.setText(usable
                ? "'시간' 항목에 적은 시각을 기준으로 알려 드려요. (예: 14:30, 오후 2시)"
                : "이 태그에는 '시간' 항목이 없어서 알림을 쓸 수 없어요.");
    }

    private FieldRow findRow(String key) {
        for (FieldRow row : rows) {
            if (row.key.equals(key)) {
                return row;
            }
        }
        return null;
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, "입력 확인", JOptionPane.WARNING_MESSAGE);
    }

    /** 입력 항목 한 줄: [체크박스] 항목이름: [입력칸] — 체크를 끄면 입력칸이 꺼지고 이름에 취소선이 그어진다. */
    private class FieldRow {
        final String key;
        final JCheckBox use = new JCheckBox();
        final JLabel label;
        final JTextField input = new JTextField(24);

        FieldRow(String key, boolean required) {
            this.key = key;
            this.label = new JLabel(key + ":");
            use.setSelected(true);
            use.setEnabled(!required);
            use.setToolTipText(required ? "제목은 꼭 입력해야 해요." : "체크를 끄면 이 항목은 일정에서 제외돼요.");
            use.addActionListener(e -> setUsed(use.isSelected()));
        }

        void setUsed(boolean used) {
            input.setEnabled(used);
            Font base = label.getFont();
            Map<TextAttribute, Object> attrs = new HashMap<>(base.getAttributes());
            attrs.put(TextAttribute.STRIKETHROUGH, used ? Boolean.FALSE : TextAttribute.STRIKETHROUGH_ON);
            label.setFont(base.deriveFont(attrs));
            label.setForeground(used ? Color.BLACK : Color.GRAY);
        }

        void addTo(JPanel panel, int y) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = y;
            c.insets = new Insets(4, 2, 4, 6);
            c.anchor = GridBagConstraints.WEST;
            c.gridx = 0;
            panel.add(use, c);
            c.gridx = 1;
            panel.add(label, c);
            c.gridx = 2;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            panel.add(input, c);
        }
    }

    /** 비어 있을 때 회색 안내 글자(예: YYYY.MM.DD)를 보여 주는 입력칸 */
    private static class HintTextField extends JTextField {
        private final String hint;

        HintTextField(String hint, int columns) {
            super(columns);
            this.hint = hint;
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty() && !isFocusOwner()) {
                FontMetrics fm = g.getFontMetrics(getFont());
                g.setColor(Color.GRAY);
                g.setFont(getFont());
                g.drawString(hint, getInsets().left + 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            }
        }
    }
}