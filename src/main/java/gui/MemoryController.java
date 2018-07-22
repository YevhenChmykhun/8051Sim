package gui;

import gui.util.IntegerUtil;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.Pane;
import javafx.util.Callback;
import org.apache.commons.lang3.StringUtils;
import simulator.memory.ExternalCode;
import simulator.memory.ExternalData;
import simulator.memory.Memory;
import simulator.memory.datatype.UnsignedInt8;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class MemoryController implements Updatable {

    private static final int HEX_RADIX = 16;

    private static final String REGEX_ADDRESS_PREFIX = "([dDcCxX]:)";

    @FXML
    private TextField addressTextField;

    @FXML
    private Button lockUnlockButton;

    @FXML
    private ChoiceBox<String> tableRowLengthChoiceBox;

    @FXML
    private TableView<MemoryRow> memoryTableView;

    private MainWindow mainWindow;

    @FXML
    public void initialize() {

        // initialize address text field
        addressTextField.setOnAction(event -> {

            // validate input
            String text = addressTextField.getText();
            if (StringUtils.isBlank(text) || !text.matches(REGEX_ADDRESS_PREFIX + IntegerUtil.REGEX_HEX_OCT_DEC_INTEGER)) {
                return;
            }

            // fill the table with data
            update();
        });

        // initialize lock/unlock button
        lockUnlockButton.setOnAction(event -> {
            memoryTableView.setEditable(!memoryTableView.isEditable());
            lockUnlockButton.setText(memoryTableView.isEditable() ? "Lock" : "Unlock");
        });

        // initialize table row length choice box
        tableRowLengthChoiceBox.setItems(FXCollections.observableArrayList("16", "8"));
        tableRowLengthChoiceBox.getSelectionModel().selectFirst();
        tableRowLengthChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            initializeMemoryTableView();
            fillTable(getMemory(), Integer.parseInt(newValue));
        });

        initializeMemoryTableView();

        // fill the table with data
        update();
    }

    private void initializeMemoryTableView() {
        memoryTableView.getColumns().clear();

        TableColumn<MemoryRow, MemoryRow> addressColumn = new TableColumn<>();
        addressColumn.setPrefWidth(100);
        addressColumn.setCellValueFactory(param -> new ReadOnlyObjectWrapper<>(param.getValue()));
        addressColumn.setCellFactory(new Callback<TableColumn<MemoryRow, MemoryRow>, TableCell<MemoryRow, MemoryRow>>() {
            @Override
            public TableCell<MemoryRow, MemoryRow> call(TableColumn<MemoryRow, MemoryRow> param) {
                return new TableCell<MemoryRow, MemoryRow>() {
                    @Override
                    protected void updateItem(MemoryRow item, boolean empty) {
                        super.updateItem(item, empty);

                        // for each row show a memory type and a start address of that row
                        if (getTableRow() != null && item != null) {
                            setText(item.getStartAddress());
                        } else {
                            setText("");
                        }
                    }
                };
            }
        });
        memoryTableView.getColumns().add(addressColumn);

        List<TableColumn<MemoryRow, String>> columns = new LinkedList<>();
        int columnsNumber = Integer.parseInt(tableRowLengthChoiceBox.getSelectionModel().getSelectedItem());
        for (int i = 0; i < columnsNumber; i++) {
            TableColumn<MemoryRow, String> column = new TableColumn<>();
            column.setPrefWidth(45);

            // make it possible to correctly match a memory cell value with the corresponding table column
            column.setId(Integer.toString(i));

            // match a memory cell value with the corresponding table column
            column.setCellValueFactory((TableColumn.CellDataFeatures<MemoryRow, String> param) -> {
                String id = param.getTableColumn().getId();
                return new ReadOnlyStringWrapper(param.getValue().getCellValue(Integer.parseInt(id)));
            });

            // make table cells editable
            column.setCellFactory(TextFieldTableCell.forTableColumn());

            // update the memory when a table cell was edited
            column.setOnEditCommit(event -> {

                // validate input
                String text = event.getNewValue();
                if (StringUtils.isBlank(text) || !text.matches(REGEX_ADDRESS_PREFIX + IntegerUtil.REGEX_HEX_OCT_DEC_INTEGER)) {
                    return;
                }

                MemoryRow memoryRow = event.getRowValue();
                int startAddress = IntegerUtil.parseInt(memoryRow.getStartAddress().substring(2));
                int columnIndex = Integer.parseInt(event.getTableColumn().getId());
                getMemory().setCellValue(startAddress + columnIndex, new UnsignedInt8(Integer.parseInt(text, HEX_RADIX)));

                // update the whole GUI
                mainWindow.updateUserInterface();
            });

            columns.add(column);
        }
        memoryTableView.getColumns().addAll(columns);

        memoryTableView.widthProperty().addListener((source, oldWidth, newWidth) -> {

            // hide table header
            Pane header = (Pane) memoryTableView.lookup("TableHeaderRow");
            if (header.isVisible()) {
                header.setMaxHeight(0);
                header.setMinHeight(0);
                header.setPrefHeight(0);
                header.setVisible(false);
            }
        });
    }

    public void setMainWindow(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
    }

    @Override
    public void update() {
        int tableRowLength = Integer.parseInt(tableRowLengthChoiceBox.getSelectionModel().getSelectedItem());
        fillTable(getMemory(), tableRowLength);
    }

    private Memory getMemory() {
        switch (getRequestedMemoryType()) {
            case "D":
                return MainWindow.data;
            case "C":
                return new ExternalCode();
            case "X":
                return new ExternalData();
        }

        throw new RuntimeException("Unsupported memory type");
    }

    private void fillTable(Memory memory, int tableRowLength) {

        // a list of transformed memory data
        ObservableList<MemoryRow> tableData = FXCollections.observableArrayList();

        // a list of the length tableRowLength that contains a part of the memory data in form of HEX strings
        List<String> row = new ArrayList<>();

        int requestedAddress = getRequestedMemoryAddress();
        int address = requestedAddress;
        do {

            // reset address when it is out of the memory size
            if (address == memory.getMemorySize()) {
                address = -1;
                continue;
            }

            // a memory cell value in form of a HEX string
            String hex = IntegerUtil.toString(memory.getCellValue(address).toInt(), HEX_RADIX, 2);
            row.add(hex.toUpperCase());

            // as soon as the row reaches the size of tableRowLength, it is combined in a separate entity that contains
            // the row (a part of the memory of the length tableRowLength)
            // and a start address of that part of the memory
            if (((address + 1) % tableRowLength) - (requestedAddress % tableRowLength) == 0) {

                // calculate the number of leading zeros taking into account the size of the memory
                int leadingZeros = (int) (Math.log(memory.getMemorySize()) / Math.log(HEX_RADIX));

                // build start address of a row taking into account offset
                int adjustedAddress = address + 1 - tableRowLength;
                adjustedAddress = adjustedAddress >= 0 ? adjustedAddress : adjustedAddress + memory.getMemorySize();

                String addressHex = IntegerUtil.toString(adjustedAddress, HEX_RADIX, leadingZeros);
                String startAddress = getRequestedMemoryType() + ":0x" + addressHex.toUpperCase();

                tableData.add(new MemoryRow(startAddress, new ArrayList<>(row)));
                row.clear();
            }
        } while (++address != requestedAddress);

        memoryTableView.setItems(tableData);
    }

    private String getRequestedMemoryType() {
        return addressTextField.getText().substring(0, 1).toUpperCase();
    }

    private int getRequestedMemoryAddress() {
        return IntegerUtil.parseInt(addressTextField.getText().substring(2));
    }

    private class MemoryRow {
        private String startAddress;
        private List<String> cellValues;

        MemoryRow(String startAddress, List<String> values) {
            this.startAddress = startAddress;
            this.cellValues = values;
        }

        String getCellValue(int index) {
            return cellValues.get(index);
        }

        String getStartAddress() {
            return startAddress;
        }
    }

}