package com.chris.codegen.utils;

import com.chris.codegen.entity.ColumnEntity;
import com.chris.codegen.entity.TableEntity;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 代码生成器   工具类
 *
 * @author chris
 *
 * @since 2016年12月19日 下午11:40:24
 */
public class GenUtils {

    public static List<String> getTemplates(){
        List<String> templates = new ArrayList<String>();
        templates.add("template/Entity.java.vm");
        templates.add("template/Dao.java.vm");
        templates.add("template/Dao.xml.vm");
        templates.add("template/Service.java.vm");
        templates.add("template/ServiceImpl.java.vm");
        templates.add("template/Controller.java.vm");
        /*templates.add("template/list.html.vm");
        templates.add("template/list.js.vm");*/
        templates.add("template/menu.sql.vm");

        templates.add("template/index.vue.vm");
        templates.add("template/add-or-update.vue.vm");
        return templates;
    }

    public static List<String> excludeQryColumns() {
        return Arrays.asList(new String[]{"create_user_id", "create_time", "update_user_id", "update_time"});
    }


    /**
     * 生成代码
     */
    public static void generatorCode(Map<String, String> table,
                                     List<Map<String, String>> columns, ZipOutputStream zip) {
        //配置信息
        Configuration config = getConfig();
        boolean hasBigDecimal = false;
        //表信息
        TableEntity tableEntity = new TableEntity();
        tableEntity.setTableName(table.get("tableName" ));
        tableEntity.setComments(table.get("tableComment" ));
        //表名转换成Java类名
        String className = tableToJava(tableEntity.getTableName(), getTablePrefix());
        tableEntity.setClassName(convertClassName(className, "ibms"));
        tableEntity.setClassname(StringUtils.uncapitalize(className));

        //列信息
        List<ColumnEntity> columsList = new ArrayList<>();
        for(Map<String, String> column : columns){
            ColumnEntity columnEntity = new ColumnEntity();
            columnEntity.setColumnName(column.get("columnName" ));
            columnEntity.setDataType(column.get("dataType" ));
            columnEntity.setComments(column.get("columnComment" ));
            columnEntity.setExtra(column.get("extra" ));

            //列名转换成Java属性名
            String attrName = columnToJava(columnEntity.getColumnName());
            columnEntity.setAttrName(attrName);
            columnEntity.setAttrname(StringUtils.uncapitalize(attrName));

            //列的数据类型，转换成Java类型
            String attrType = config.getString(columnEntity.getDataType(), "unknowType" );
            columnEntity.setAttrType(attrType);
            if (!hasBigDecimal && attrType.equals("BigDecimal" )) {
                hasBigDecimal = true;
            }
            //是否主键
            if ("PRI".equalsIgnoreCase(column.get("columnKey" )) && tableEntity.getPk() == null) {
                tableEntity.setPk(columnEntity);
            }

            columsList.add(columnEntity);
        }
        tableEntity.setColumns(columsList);

        //没主键，则第一个字段为主键
        if (tableEntity.getPk() == null) {
            tableEntity.setPk(tableEntity.getColumns().get(0));
        }

        //设置velocity资源加载器
        Properties prop = new Properties();
        prop.put("file.resource.loader.class", "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader" );
        Velocity.init(prop);
        String mainPath = config.getString("mainPath" );
        mainPath = StringUtils.isBlank(mainPath) ? "com.chris" : mainPath;
        //封装模板数据
        Map<String, Object> map = new HashMap<>();
        map.put("tableName", tableEntity.getTableName());
        map.put("comments", tableEntity.getComments());
        map.put("pk", tableEntity.getPk());
        map.put("className", tableEntity.getClassName());
        map.put("classname", tableEntity.getClassname());
        map.put("pathName", tableEntity.getClassname().toLowerCase());
        map.put("columns", tableEntity.getColumns());
        map.put("queryColumns", tableEntity.getColumns().stream().filter(column -> {
            return !excludeQryColumns().contains(column.getColumnName().toLowerCase());
        }).collect(Collectors.toList()));
        map.put("hasBigDecimal", hasBigDecimal);
        map.put("mainPath", mainPath);
        map.put("package", config.getString("package" ));

        String moduleName = getModuleName(tableEntity.getTableName());
        map.put("moduleName", moduleName);
        map.put("author", config.getString("author" ));
        map.put("email", config.getString("email" ));
        map.put("datetime", createDateTime());

        boolean hasUpdateInfo = getHasUpdateInfo(tableEntity.getColumns());
        map.put("hasUpdateInfo", hasUpdateInfo);
        map.put("custColumns", map.get(hasUpdateInfo ? "queryColumns" : "columns"));
        map.put("hasTime", getHasTimeValue((List<ColumnEntity>) map.get("custColumns")));
        VelocityContext context = new VelocityContext(map);

        //获取模板列表
        List<String> templates = getTemplates();
        for (String template : templates) {
            //渲染模板
            StringWriter sw = new StringWriter();
            Template tpl = Velocity.getTemplate(template, "UTF-8" );
            tpl.merge(context, sw);

            try {
                //添加到zip
                zip.putNextEntry(new ZipEntry(getFileName(template, tableEntity.getClassName(), config.getString("package" ), moduleName)));
                IOUtils.write(sw.toString(), zip, "UTF-8" );
                IOUtils.closeQuietly(sw);
                zip.closeEntry();
            } catch (IOException e) {
                throw new CommonException("渲染模板失败，表名：" + tableEntity.getTableName(), e);
            }
        }
    }

    private static String convertClassName(String className, String ... keys) {
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (className.toLowerCase().startsWith(key)) {
                return key.toUpperCase() + className.substring(key.length());
            }
        }
        return className;
    }

    private static boolean getHasTimeValue(List<ColumnEntity> columns) {
        for (int i = 0; i < columns.size(); i++) {
            String colName = columns.get(i).getColumnName().toLowerCase();
            if (colName.indexOf("_date") >= 0 || colName.indexOf("_time") >= 0 || colName.indexOf("birthday") >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String getTablePrefix() {
        return "t_,sp_,tb_";
    }

    private static boolean getHasUpdateInfo(List<ColumnEntity> columns) {
        int count = 0;
        List<String> excludeQryColumns = excludeQryColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (excludeQryColumns.contains(columns.get(i).getColumnName().toLowerCase())) {
                count++;
            }
        }
        return count == excludeQryColumns.size();
    }

    private static String getModuleName(String tableName) {
        String tbName = tableName.toLowerCase();
        if (tbName.startsWith("base_")) {
            return "base";
        } else if (tbName.startsWith("ibms_")) {
            return "ibms";
        } else if (tbName.startsWith("sp_")) {
            return "busi";
        } else if (tbName.startsWith("tb_dev") || tbName.equals("tb_actived_device_info")) {
            return "devctrl";
        } else if (tbName.startsWith("tb_tea")) {
            return "tea";
        } else if (tbName.startsWith("tb_user") || tbName.equals("t_teaman_admin_user")) {
            return "user";
        }
        return "sys";
    }

    private static String createDateTime() {
        String [] months = "Jan,Feb,Mar,Apr,May,June,July,Aug,Sep,Oct,Nov,Dec".split(",");
        String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String year = dateStr.substring(2, 4);
        String day = dateStr.substring(8, 10);
        int month = Integer.valueOf(dateStr.substring(5, 7)) - 1;
        return months[month] + " " + day + "." + year;
    }


    /**
     * 列名转换成Java属性名
     */
    public static String columnToJava(String columnName) {
        return WordUtils.capitalizeFully(columnName, new char[]{'_'}).replace("_", "" );
    }

    /**
     * 表名转换成Java类名
     */
    public static String tableToJava(String tableName, String tablePrefix) {
        if (StringUtils.isNotBlank(tablePrefix)) {
            String [] prefixs = tablePrefix.split(",");
            for (int i = 0; i < prefixs.length; i++) {
                if (tableName.toLowerCase().startsWith(prefixs[i])) {
                    tableName = tableName.replace(prefixs[i], "" );
                    break;
                }
            }
        }
        return columnToJava(tableName);
    }

    /**
     * 获取配置信息
     */
    public static Configuration getConfig() {
        try {
            return new PropertiesConfiguration("generator.properties" );
        } catch (ConfigurationException e) {
            throw new CommonException("获取配置文件失败，", e);
        }
    }

    /**
     * 获取文件名
     */
    public static String getFileName(String template, String className, String packageName, String moduleName) {
        String packagePath = "main" + File.separator + "java" + File.separator;
        if (StringUtils.isNotBlank(packageName)) {
            packagePath += packageName.replace(".", File.separator) + File.separator + moduleName + File.separator;
        }

        if (template.contains("Entity.java.vm" )) {
            return packagePath + "entity" + File.separator + className + "Entity.java";
        }

        if (template.contains("Dao.java.vm" )) {
            return packagePath + "dao" + File.separator + className + "Dao.java";
        }

        if (template.contains("Service.java.vm" )) {
            return packagePath + "service" + File.separator + className + "Service.java";
        }

        if (template.contains("ServiceImpl.java.vm" )) {
            return packagePath + "service" + File.separator + "impl" + File.separator + className + "ServiceImpl.java";
        }

        if (template.contains("Controller.java.vm" )) {
            return packagePath + "controller" + File.separator + className + "Controller.java";
        }

        if (template.contains("Dao.xml.vm" )) {
            return "main" + File.separator + "resources" + File.separator + "mapper" + File.separator + moduleName + File.separator + className + "Dao.xml";
        }

        if (template.contains("list.html.vm" )) {
            return "main" + File.separator + "resources" + File.separator + "views" + File.separator
                    + "modules" + File.separator + moduleName + File.separator + className.toLowerCase() + ".html";
        }

        if (template.contains("list.js.vm" )) {
            return "main" + File.separator + "resources" + File.separator + "static" + File.separator + "js" + File.separator
                    + "modules" + File.separator + moduleName + File.separator + className.toLowerCase() + ".js";
        }

        if (template.contains("menu.sql.vm" )) {
            return className.toLowerCase() + "_menu.sql";
        }

        if (template.contains("index.vue.vm" )) {
            return "main" + File.separator + "resources" + File.separator + "src" + File.separator + "views" + File.separator + "modules" +
                    File.separator + moduleName + File.separator + className.toLowerCase() + ".vue";
        }

        if (template.contains("add-or-update.vue.vm" )) {
            return "main" + File.separator + "resources" + File.separator + "src" + File.separator + "views" + File.separator + "modules" +
                    File.separator + moduleName + File.separator + className.toLowerCase() + "-add-or-update.vue";
        }

        return null;
    }
}
