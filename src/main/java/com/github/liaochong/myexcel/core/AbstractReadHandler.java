/*
 * Copyright 2019 liaochong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.liaochong.myexcel.core;


import com.github.liaochong.myexcel.core.annotation.ExcelColumn;
import com.github.liaochong.myexcel.core.converter.ReadConverterContext;
import com.github.liaochong.myexcel.core.reflect.ClassFieldContainer;
import com.github.liaochong.myexcel.exception.StopReadException;
import com.github.liaochong.myexcel.utils.GlobalSettingUtil;
import com.github.liaochong.myexcel.utils.ReflectUtil;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 读取抽象
 *
 * @author liaochong
 * @version 1.0
 */
abstract class AbstractReadHandler<T> {

    private Map<Integer, Field> fieldMap;

    private T obj;

    protected Map<String, Integer> titles = new HashMap<>();

    protected SaxExcelReader.ReadConfig<T> readConfig;

    private BiConsumer<String, Integer> addTitleConsumer = (v, colNum) -> {
    };

    private ReadContext<T> context = new ReadContext<>();

    private ConvertContext convertContext;
    /**
     * Row object currently being processed
     */
    private final Row currentRow = new Row(-1);

    private Supplier<T> newInstance;

    private BiConsumer<Integer, String> fieldHandler;

    private Consumer<T> resultHandler;
    /**
     * Whether to use title for import
     */
    private boolean readWithTitle;

    public AbstractReadHandler(boolean readCsv,
                               List<T> result,
                               SaxExcelReader.ReadConfig<T> readConfig) {
        convertContext = new ConvertContext(readCsv);
        Class<T> dataType = readConfig.getDataType();
        fieldMap = ReflectUtil.getFieldMapOfExcelColumn(dataType);
        this.readConfig = readConfig;
        boolean isMapType = dataType == Map.class;
        if (fieldMap.isEmpty()) {
            addTitleConsumer = this::addTitles;
            readWithTitle = true;
        }
        setNewInstanceFunction(dataType, isMapType);
        // 全局配置获取
        setGlobalSetting(dataType, isMapType);
        setResultHandlerFunction(result, readConfig);
        setFieldHandlerFunction(isMapType);
    }

    private void setResultHandlerFunction(List<T> result, SaxExcelReader.ReadConfig<T> readConfig) {
        if (readConfig.getConsumer() != null) {
            resultHandler = v -> readConfig.getConsumer().accept(v);
        } else if (readConfig.getFunction() != null) {
            resultHandler = v -> {
                Boolean noStop = readConfig.getFunction().apply(v);
                if (!noStop) {
                    throw new StopReadException();
                }
            };
        } else {
            resultHandler = result::add;
        }
    }

    @SuppressWarnings("unchecked")
    private void setNewInstanceFunction(Class<T> dataType, boolean isMapType) {
        if (isMapType) {
            newInstance = () -> (T) new LinkedHashMap<Cell, String>();
        } else {
            newInstance = () -> {
                try {
                    return dataType.newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            };
        }
    }

    private void setGlobalSetting(Class<T> dataType, boolean isMapType) {
        if (isMapType) {
            return;
        }
        ClassFieldContainer classFieldContainer = ReflectUtil.getAllFieldsOfClass(dataType);
        GlobalSettingUtil.setGlobalSetting(classFieldContainer, convertContext.getGlobalSetting());

        List<Field> fields = classFieldContainer.getFieldsByAnnotation(ExcelColumn.class);
        fields.forEach(field -> {
            ExcelColumn excelColumn = field.getAnnotation(ExcelColumn.class);
            if (excelColumn == null) {
                return;
            }
            ExcelColumnMapping mapping = ExcelColumnMapping.mapping(excelColumn);
            convertContext.getExcelColumnMappingMap().put(field, mapping);
        });
    }

    @SuppressWarnings("unchecked")
    private void setFieldHandlerFunction(boolean isMapType) {
        if (isMapType) {
            fieldHandler = (colNum, content) -> ((Map<Cell, String>) obj).put(new Cell(currentRow.getRowNum(), colNum), content);
        } else {
            fieldHandler = (colNum, content) -> {
                Field field = fieldMap.get(colNum);
                convert(content, currentRow.getRowNum(), colNum, field);
            };
        }
    }

    protected void convert(String value, int rowNum, int colNum, Field field) {
        if (value == null || field == null) {
            return;
        }
        context.reset(obj, field, value, rowNum, colNum);
        ReadConverterContext.convert(obj, context, convertContext, readConfig.getExceptionFunction());
    }

    private void addTitles(String formattedValue, int thisCol) {
        if (currentRow != null && currentRow.getRowNum() == 0) {
            titles.put(formattedValue, thisCol);
        }
    }

    protected void newRow(int rowNum) {
        currentRow.setRowNum(rowNum);
        obj = newInstance.get();
    }

    protected void setRecordAsNull() {
        obj = null;
    }

    protected void handleField(Integer colNum, String content) {
        if (currentRow == null || obj == null || colNum < 0) {
            return;
        }
        content = readConfig.getTrim().apply(content);
        this.addTitleConsumer.accept(content, colNum);
        if (readConfig.getRowFilter().test(currentRow)) {
            fieldHandler.accept(colNum, content);
        }
    }

    protected void handleResult() {
        this.initFieldMap();
        if (!readConfig.getRowFilter().test(currentRow)) {
            return;
        }
        if (!readConfig.getBeanFilter().test(obj)) {
            return;
        }
        if (readWithTitle && currentRow.getRowNum() == 0) {
            return;
        }
        resultHandler.accept(obj);
    }

    private void initFieldMap() {
        if (currentRow.getRowNum() != 0 || !fieldMap.isEmpty()) {
            return;
        }
        Map<String, Field> titleFieldMap = ReflectUtil.getFieldMapOfTitleExcelColumn(readConfig.getDataType());
        fieldMap = new HashMap<>(titleFieldMap.size());
        titles.forEach((k, v) -> {
            fieldMap.put(v, titleFieldMap.get(k));
        });
    }
}
