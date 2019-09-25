package org.hswebframework.ezorm.rdb.mapping.jpa;

import lombok.SneakyThrows;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.hswebframework.ezorm.core.ValueCodec;
import org.hswebframework.ezorm.rdb.codec.DateTimeCodec;
import org.hswebframework.ezorm.rdb.codec.NumberValueCodec;
import org.hswebframework.ezorm.rdb.mapping.DefaultEntityColumnMapping;
import org.hswebframework.ezorm.rdb.mapping.annotation.Type;
import org.hswebframework.ezorm.rdb.metadata.*;
import org.hswebframework.utils.ClassUtils;

import javax.persistence.*;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.JDBCType;
import java.util.Date;
import java.util.Optional;

public class JpaEntityTableMetadataParserProcessor {

    private DefaultEntityColumnMapping mapping;

    private Class<?> entityType;

    private RDBTableMetadata tableMetadata;

    public JpaEntityTableMetadataParserProcessor(RDBTableMetadata tableMetadata, Class<?> entityType) {
        this.tableMetadata = tableMetadata;
        this.entityType = entityType;
        this.mapping = new DefaultEntityColumnMapping(tableMetadata, entityType);
        tableMetadata.addFeature(this.mapping);
    }

    public void process() {
        PropertyDescriptor[] descriptors = BeanUtilsBean.getInstance()
                .getPropertyUtils()
                .getPropertyDescriptors(entityType);

        //List<Runnable> lastRun = new ArrayList<>();

        Table table = ClassUtils.getAnnotation(entityType, Table.class);
        int idx = 0;

        for (Index index : table.indexes()) {
            String name = index.name();
            if (name.isEmpty()) {
                name = tableMetadata.getName().concat("_idx_").concat(String.valueOf(idx++));
            }
            RDBIndexMetadata indexMetadata = new RDBIndexMetadata();
            indexMetadata.setUnique(index.unique());
            indexMetadata.setName(name);

            //id asc,
            String[] columnList = index.columnList().split("[,]");
            for (String str : columnList) {
                String[] columnAndSort = str.split("[ ]+");
                RDBIndexMetadata.IndexColumn column = new RDBIndexMetadata.IndexColumn();
                column.setColumn(columnAndSort[0].trim());
                if (columnAndSort.length > 1) {
                    column.setSort(columnAndSort[1].equalsIgnoreCase("desc") ? RDBIndexMetadata.IndexSort.desc : RDBIndexMetadata.IndexSort.asc);
                }
                indexMetadata.getColumns().add(column);
            }
            tableMetadata.addIndex(indexMetadata);
        }

        for (PropertyDescriptor descriptor : descriptors) {
            Column column = getAnnotation(entityType, descriptor, Column.class);
            if (column != null) {
                handleColumnAnnotation(descriptor, column);
            }
            JoinColumns joinColumns = getAnnotation(entityType, descriptor, JoinColumns.class);
            if (null != joinColumns) {
                for (JoinColumn joinColumn : joinColumns.value()) {
                    handleJoinColumnAnnotation(joinColumn);
                }
            }
            JoinColumn joinColumn = getAnnotation(entityType, descriptor, JoinColumn.class);
            if (null != joinColumn) {
                handleJoinColumnAnnotation(joinColumn);
            }
        }

        //  lastRun.forEach(Runnable::run);
    }

    private void handleJoinColumnAnnotation(JoinColumn column) {

    }

    @SneakyThrows
    public <T> T resolveType(Class<T> type) {
        // TODO: 2019-09-25
        return type.newInstance();
    }

    private void handleColumnAnnotation(PropertyDescriptor descriptor, Column column) {
        //另外一个表
        if (!column.table().isEmpty() && !column.table().equals(tableMetadata.getName())) {
            mapping.addMapping(column.table().concat(".").concat(column.name()), descriptor.getName());
            return;
        }
        String columnName;

        if (!column.name().isEmpty()) {
            columnName = column.name();
        } else {
            columnName = descriptor.getName();
        }
        mapping.addMapping(columnName, descriptor.getName());
        RDBColumnMetadata metadata = tableMetadata.getColumn(columnName).orElseGet(tableMetadata::newColumn);
        metadata.setName(columnName);
        metadata.setAlias(descriptor.getName());
        metadata.setJavaType(descriptor.getPropertyType());
        metadata.setLength(column.length());
        metadata.setPrecision(column.precision());
        metadata.setScale(column.scale());
        metadata.setNotNull(!column.nullable());
        metadata.setUpdatable(column.updatable());
        if (!column.columnDefinition().isEmpty()) {
            metadata.setColumnDefinition(column.columnDefinition());
        }

        Type type = getAnnotation(entityType, descriptor, Type.class);
        if (type != null) {
            if (type.codec() != ValueCodec.class) {
                metadata.setValueCodec(resolveType(type.codec()));
            }
            if (type.type() != DataType.class) {
                metadata.setType(resolveType(type.type()));
            } else if (!type.typeId().isEmpty()) {
                metadata.setType(CustomDataType.of(type.typeId(), type.typeId(), type.jdbcType()));
            } else {
                metadata.setType(JdbcDataType.of(type.jdbcType(), metadata.getJavaType()));
            }
        }
        if (metadata.getType() == null) {
            tableMetadata.getDialect()
                    .convertJdbcType(metadata.getJavaType())
                    .ifPresent(jdbcType -> metadata.setJdbcType(jdbcType, metadata.getJavaType()));
        }
        metadata.setDataType(tableMetadata.getDialect().createColumnDataType(metadata));

        Optional.ofNullable(getAnnotation(entityType, descriptor, Id.class))
                .ifPresent(id -> metadata.setPrimaryKey(true));
        if (Date.class.isAssignableFrom(metadata.getJavaType())) {
            metadata.setValueCodec(new DateTimeCodec("yyyy-MM-dd HH:mm:ss", metadata.getJavaType()));
        } else if (Number.class.isAssignableFrom(metadata.getJavaType())) {
            metadata.setValueCodec(new NumberValueCodec(metadata.getJavaType()));
        }
        tableMetadata.addColumn(metadata);
    }


    private static <T extends Annotation> T getAnnotation(Class entityClass, PropertyDescriptor descriptor, Class<T> type) {
        T ann = null;

        Method read = descriptor.getReadMethod(),
                write = descriptor.getWriteMethod();
        if (read != null) {
            ann = getAnnotation(read, type);
        }
        if (null == ann && write != null) {
            ann = getAnnotation(write, type);
        }
        try {
            Field field = entityClass.getDeclaredField(descriptor.getName());
            ann = field.getAnnotation(type);
        } catch (@SuppressWarnings("all") NoSuchFieldException ignore) {
            if (entityClass.getSuperclass() != Object.class) {
                return getAnnotation(entityClass.getSuperclass(), descriptor, type);
            }
        }
        return ann;
    }

    private static <T extends Annotation> T getAnnotation(Method method, Class<T> annotation) {
        T ann = method.getAnnotation(annotation);
        if (ann != null) {
            return ann;
        } else {
            Class clazz = method.getDeclaringClass();
            Class superClass = clazz.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                try {
                    //父类方法
                    Method suMethod = superClass.getMethod(method.getName(), method.getParameterTypes());
                    return getAnnotation(suMethod, annotation);
                } catch (NoSuchMethodException e) {
                    return null;
                }
            }
        }
        return ann;
    }

}