/**
 * Copyright 2009-2018 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.mapping;

import org.apache.ibatis.binding.MapperMethod;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author 张富生 15210147315@163.com
 * @Date: Create Time 2018-07-10 16:10
 * 功能描述：对符合特定语法的sql进行格式操作，可以简化XML中标签的使用和优化Mybatis注解的使用
 * 语法描述：
 * 例如：传递一个参数值，我们可以写条件判断后边的sql是否拼接执行
 * #if(:[userName] != null){ and userName = "zhangsan"}-- 参数是一个对象
 */
public class FormatSql {

    public static Result formatSql(String sql, Object parameterObject, List<ParameterMapping> parameterMappings) {
        HashMap<String, ParameterMapping> map1 = new HashMap<>();
        for (ParameterMapping parameterMapping : parameterMappings) {
            map1.put(parameterMapping.getProperty(),parameterMapping);
        }
        Map parameterObjectToMap = Utils.objectToMap(parameterObject);
        if (parameterObject != null && parameterObject instanceof MapperMethod.ParamMap && parameterObjectToMap.size() == 0) {
            parameterObjectToMap = (MapperMethod.ParamMap) parameterObject;
        }
        if (sql.contains("#if(")) { // 此处可以替换成正则表达式 #if(){}
            int i = appearNumber(sql, "#if\\(");
            ArrayList<String> strings = new ArrayList<>();
            ArrayList<StrIf> stringsIf = new ArrayList<>(i);
            boolean flag = true;
            int index = 1;
            for (int j = i; j >= 0; j--) {
                if (flag) {
                    strings.add(sql.substring(0, sql.indexOf("#if(")));
                    sql = sql.replace(sql.substring(0, sql.indexOf("#if(")), "");
                    flag = false;
                } else {
                    strings.add(sql.substring(sql.indexOf("#if("), sql.indexOf("}") + 1));
                    stringsIf.add(new StrIf(sql.substring(sql.indexOf("#if("), sql.indexOf("}") + 1), index++));
                    sql = sql.replace(sql.substring(sql.indexOf("#if("), sql.indexOf("}") + 1), "");
                    if (sql.trim().indexOf("#if") > 0) {
                        strings.add(sql.substring(0, sql.indexOf("#if(")));
                        sql = sql.replace(sql.substring(0, sql.indexOf("#if(")), "");
                        index += 1;
                    }
                }
            }
            ArrayList<KeyValue> keyValues = new ArrayList<>();
            for (StrIf s : stringsIf) {
                String strIf = s.getStr();
                keyValues.add(new KeyValue(strIf.substring(strIf.indexOf("#if(") + 3, strIf.lastIndexOf(")") + 1)
                        , strIf.substring(strIf.indexOf("{") + 1, strIf.indexOf("}")), s.getIndex()));
            }
            HashMap<Integer, KeyValue> map = new HashMap<>(keyValues.size());
            ScriptEngineManager manager = new ScriptEngineManager();
            ScriptEngine engine = manager.getEngineByName("js");
            for (KeyValue keyValue : keyValues) {
                String key = keyValue.getKey();
                String[] split = key.split(":\\[");
                for (String s : split) {
                    if (s.contains("]")) {
                        engine.put(s.substring(0, s.lastIndexOf("]")).trim().replace(".", ""),
                                parameterObjectToMap.get(s.substring(0, s.lastIndexOf("]")).trim()));
                    }
                }
                try {
                    boolean b = (boolean) engine.eval(key.replace(":[", "").replace("]", "").replace(".",""));
                    keyValue.setAddStr(b);
                    map.put(keyValue.getIndex(), keyValue);
                    if (!b) {  // 有点问题
                        map1.remove(key.substring(key.indexOf(":[") + 2, key.lastIndexOf("]")).trim());
                    }
                } catch (ScriptException e) {
                    e.printStackTrace();
                }
            }

            StringBuffer stringBuffer = new StringBuffer(" ");
            for (int j = 0; j < strings.size(); j++) {
                String string = null;
                KeyValue keyValue = map.get(j);
                if (keyValue == null) {
                    string = strings.get(j);
                } else if (keyValue.isAddStr()) {
                    string = keyValue.getValue();
                }
                if (string != null) {
                    stringBuffer.append(string.trim()).append(" ");
                }
            }
            parameterMappings = new ArrayList<>();
            for (String s : map1.keySet()) {
                parameterMappings.add(map1.get(s));
            }
            sql = stringBuffer.toString();
            System.out.println(sql);
            return new Result(sql, parameterMappings);
        }
        return new Result(sql, parameterMappings);
    }

    /**
     * 获取指定字符串出现的次数
     *
     * @param srcText  源字符串
     * @param findText 要查找的字符串
     * @return
     */
    public static int appearNumber(String srcText, String findText) {
        int count = 0;
        Pattern p = Pattern.compile(findText);
        Matcher m = p.matcher(srcText);
        while (m.find()) {
            count++;
        }
        return count;
    }
}

class Utils {
    public static Map objectToMap(Object thisObj) {
        Map map = new HashMap();
        if (thisObj == null) {
            return map;
        }
        Class c;
        try {
            c = Class.forName(thisObj.getClass().getName());
            if(c == null || c.getPackage().getName().equals("java.lang")){ // 判断是否是基本类型，待优化
                return map;
            }
            System.out.println(c.getPackage().toString());
            //获取所有的方法
            Method[] m = c.getMethods();
            for (int i = 0; i < m.length; i++) {   //获取方法名
                String method = m[i].getName();
                //获取get开始的方法名
                if (method.startsWith("get") && !method.contains("getClass")) {
                    try {
                        //获取对应对应get方法的value值
                        Object value = m[i].invoke(thisObj);
                        if (value != null) {
                            //截取get方法除get意外的字符 如getUserName-->UserName
                            String key = method.substring(3);
                            //将属性的第一个值转为小写
                            key = key.substring(0, 1).toLowerCase() + key.substring(1);
                            //将属性key,value放入对象
                            Map map1 = objectToMap(value);
                            if (map1.size() > 0) {
                                for (Object o : map1.keySet()) {
                                    map.put(key + "." + o, map1.get(o));
                                }
                            } else {
                                map.put(key, value);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return map;
    }

}

class KeyValue {
    String key;
    String value;
    boolean addStr;
    int index;

    public KeyValue(String key, String value, int index) {
        this.key = key;
        this.value = value;
        this.index = index;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isAddStr() {
        return addStr;
    }

    public void setAddStr(boolean addStr) {
        this.addStr = addStr;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }
}

class StrIf {
    String str;
    int index;

    public StrIf(String str, int index) {
        this.str = str;
        this.index = index;
    }

    public String getStr() {
        return str;
    }

    public void setStr(String str) {
        this.str = str;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }
}

class Result{
    String sql;
    List<ParameterMapping> parameterMappings;

    public Result(String sql, List<ParameterMapping> parameterMappings) {
        this.sql = sql;
        this.parameterMappings = parameterMappings;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public List<ParameterMapping> getParameterMappings() {
        return parameterMappings;
    }

    public void setParameterMappings(List<ParameterMapping> parameterMappings) {
        this.parameterMappings = parameterMappings;
    }
}