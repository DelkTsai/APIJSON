/*Copyright ©2016 TommyLemon(https://github.com/TommyLemon/APIJSON)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/

package zuo.biao.apijson.server;

import static zuo.biao.apijson.JSONObject.KEY_ID;
import static zuo.biao.apijson.JSONObject.KEY_USER_ID;
import static zuo.biao.apijson.server.Operation.ADD;
import static zuo.biao.apijson.server.Operation.DISALLOW;
import static zuo.biao.apijson.server.Operation.NECESSARY;
import static zuo.biao.apijson.server.Operation.PUT;
import static zuo.biao.apijson.server.Operation.REMOVE;
import static zuo.biao.apijson.server.Operation.REPLACE;
import static zuo.biao.apijson.server.Operation.UNIQUE;
import static zuo.biao.apijson.server.Operation.VERIFY;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import javax.activation.UnsupportedDataTypeException;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import zuo.biao.apijson.JSON;
import zuo.biao.apijson.JSONResponse;
import zuo.biao.apijson.Log;
import zuo.biao.apijson.RequestMethod;
import zuo.biao.apijson.RequestRole;
import zuo.biao.apijson.StringUtil;
import zuo.biao.apijson.server.model.Test;

/**结构类
 * 增删改查: OPERATION(ADD,REPLACE,PUT,REMOVE)   OPERATION:{key0:value0, key1:value1 ...}
 * 对值校验: VERIFY:{key0:value0, key1:value1 ...}  (key{}:range,key$:"%m%"等)
 * 对值重复性校验: UNIQUE:"key0:, key1 ..."  (UNIQUE:"phone,email" 等)
 * @author Lemon
 */
public class Structure {
	private static final String TAG = "Structure";



	private Structure() {}




	/**从request提取target指定的内容
	 * @param method
	 * @param name
	 * @param target
	 * @param request
	 * @param creator 
	 * @return
	 * @throws Exception
	 */
	public static JSONObject parseRequest(@NotNull final RequestMethod method, final String name
			, final JSONObject target, final JSONObject request, final SQLCreator creator) throws Exception {
		Log.i(TAG, "parseRequest  method = " + method  + "; name = " + name
				+ "; target = \n" + JSON.toJSONString(target)
				+ "\n request = \n" + JSON.toJSONString(request));
		if (target == null || request == null) {// || request.isEmpty()) {
			Log.i(TAG, "parseRequest  target == null || request == null >> return null;");
			return null;
		}

		if (RequestRole.get(request.getString(JSONRequest.KEY_ROLE)) == RequestRole.ADMIN) {
			throw new IllegalArgumentException("角色设置错误！不允许在写操作Request中传 " + name + 
					":{ " + JSONRequest.KEY_ROLE + ":admin } ！");
		}

		//解析
		return parse(name, target, request, creator, new OnParseCallback() {

			@Override
			public JSONObject onParseJSONObject(String key, JSONObject tobj, JSONObject robj) throws Exception {
				//				Log.i(TAG, "parseRequest.parse.onParseJSONObject  key = " + key + "; robj = " + robj);
				if (robj == null) {
					if (tobj != null) {//不允许不传Target中指定的Table
						throw new IllegalArgumentException(method.name() + "请求，请在 " + name + " 内传 " + key + ":{} ！");
					}
				} else if (zuo.biao.apijson.JSONObject.isTableKey(key)) {
					if (method == RequestMethod.POST) {
						if (robj.containsKey(KEY_ID)) {
							throw new IllegalArgumentException("POST请求，" + name + "/" + key + " 不能传 " + KEY_ID + " ！");
						}
					} else {
						if (RequestMethod.isQueryMethod(method) == false) {
							verifyId(method.name(), name, key, robj, KEY_ID, true);
							verifyId(method.name(), name, key, robj, KEY_USER_ID, false);
						}
					}
				} 

				return parseRequest(method, key, tobj, robj, creator);
			}
		});

	}
	
	/**
	 * @param method
	 * @param name
	 * @param key
	 * @param robj
	 * @param idKey
	 * @param atLeastOne 至少有一个不为null
	 */
	private static void verifyId(@NotNull String method, @NotNull String name, @NotNull String key
			, @NotNull JSONObject robj, @NotNull String idKey, boolean atLeastOne) {
		//单个修改或删除
		Object id = null;
		try {
			id = robj.getLong(idKey); //如果必须传 id ，可在Request表中配置NECESSARY
		} catch (Exception e) {
			throw new IllegalArgumentException(method + "请求，" + name + "/" + key
					+ " 里面的 " + idKey + ":value 中value的类型只能是 Long ！");
		}
		
		//批量修改或删除
		String idInKey = idKey + "{}";
		
		JSONArray idIn = null;
		try {
			idIn = robj.getJSONArray(idInKey); //如果必须传 id{} ，可在Request表中配置NECESSARY
		} catch (Exception e) {
			throw new IllegalArgumentException(method + "请求，" + name + "/" + key
					+ " 里面的 " + idInKey + ":value 中value的类型只能是 [Long] ！");
		}
		if (idIn == null) {
			if (atLeastOne && id == null) {
				throw new IllegalArgumentException(method + "请求，" + name + "/" + key
						+ " 里面 " + idKey + " 和 " + idInKey + " 至少传其中一个！");
			}
		} else {
			if (idIn.size() > 10) { //不允许一次操作10条以上记录
				throw new IllegalArgumentException(method + "请求，" + name + "/" + key
						+ " 里面的 " + idInKey + ":[] 中[]的长度不能超过10！");
			}
			//解决 id{}: ["1' OR 1='1'))--"] 绕过id{}限制
			//new ArrayList<Long>(idIn) 不能检查类型，Java泛型擦除问题，居然能把 ["a"] 赋值进去还不报错
			for (int i = 0; i < idIn.size(); i++) {
				try {
					idIn.getLong(i);
				} catch (Exception e) {
					throw new IllegalArgumentException(method + "请求，" + name + "/" + key
							+ " 里面的 " + idInKey + ":[] 中所有项的类型都只能是Long！");
				}
			}
		}
	}


	
	/**校验并将response转换为指定的内容和结构
	 * @param method
	 * @param name
	 * @param target
	 * @param response
	 * @param callback 
	 * @param creator 
	 * @return
	 * @throws Exception
	 */
	public static JSONObject parseResponse(@NotNull final RequestMethod method, final String name
			, final JSONObject target, final JSONObject response, SQLCreator creator, OnParseCallback callback) throws Exception {
		Log.i(TAG, "parseResponse  method = " + method  + "; name = " + name
				+ "; target = \n" + JSON.toJSONString(target)
				+ "\n response = \n" + JSON.toJSONString(response));
		if (target == null || response == null) {// || target.isEmpty() {
			Log.i(TAG, "parseRequest  target == null || response == null >> return response;");
			return response;
		}

		//解析
		return parse(name, target, response, creator, callback != null ? callback : new OnParseCallback() {});
	}


	/**对request和response不同的解析用callback返回
	 * @param target
	 * @param request
	 * @param callback
	 * @param creator 
	 * @return
	 * @throws Exception 
	 */
	public static JSONObject parse(String name, JSONObject target, JSONObject real
			, SQLCreator creator, @NotNull OnParseCallback callback) throws Exception {
		if (target == null) {
			return null;
		}


		//获取配置<<<<<<<<<<<<<<<<<<<<<<<<<<<<
		JSONObject verify = target.getJSONObject(VERIFY.name());
		JSONObject add = target.getJSONObject(ADD.name());
		JSONObject put = target.getJSONObject(PUT.name());
		JSONObject replace = target.getJSONObject(REPLACE.name());

		String unique = StringUtil.getNoBlankString(target.getString(UNIQUE.name()));
		String remove = StringUtil.getNoBlankString(target.getString(REMOVE.name()));
		String necessary = StringUtil.getNoBlankString(target.getString(NECESSARY.name()));
		String disallow = StringUtil.getNoBlankString(target.getString(DISALLOW.name()));

		//不还原，传进来的target不应该是原来的
		target.remove(VERIFY.name());
		target.remove(ADD.name());
		target.remove(PUT.name());
		target.remove(REPLACE.name());

		target.remove(UNIQUE.name());
		target.remove(REMOVE.name());
		target.remove(NECESSARY.name());
		target.remove(DISALLOW.name());
		//获取配置>>>>>>>>>>>>>>>>>>>>>>>>>>>>>



		//移除字段<<<<<<<<<<<<<<<<<<<
		String[] removes = StringUtil.split(remove);
		if (removes != null && removes.length > 0) {
			for (String r : removes) {
				real.remove(r);
			}
		}
		//移除字段>>>>>>>>>>>>>>>>>>>

		//判断必要字段是否都有<<<<<<<<<<<<<<<<<<<
		String[] necessarys = StringUtil.split(necessary);
		List<String> necessaryList = necessarys == null ? new ArrayList<String>() : Arrays.asList(necessarys);
		for (String s : necessaryList) {
			if (real.get(s) == null) {//可能传null进来，这里还会通过 real.containsKey(s) == false) {
				throw new IllegalArgumentException(name
						+ " 里面不能缺少 " + s + " 等[" + necessary + "]内的任何字段！");
			}
		}
		//判断必要字段是否都有>>>>>>>>>>>>>>>>>>>


		Set<String> objKeySet = new HashSet<String>(); //不能用tableKeySet，仅判断 Table:{} 会导致 key:{ Table:{} } 绕过判断

		//解析内容<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<

		Set<Entry<String, Object>> set = new LinkedHashSet<>(target.entrySet());
		if (set.isEmpty() == false) {

			String key;
			Object tvalue;
			Object rvalue;
			for (Entry<String, Object> entry : set) {
				key = entry == null ? null : entry.getKey();
				if (key == null) {
					continue;
				}
				tvalue = entry.getValue();
				rvalue = real.get(key);
				if (callback.onParse(key, tvalue, rvalue) == false) {
					continue;
				}

				if (tvalue instanceof JSONObject) {//JSONObject，往下一级提取
					tvalue = callback.onParseJSONObject(key, (JSONObject) tvalue, (JSONObject) rvalue);

					objKeySet.add(key);
				} else if (tvalue instanceof JSONArray) {//JSONArray
					tvalue = callback.onParseJSONArray(key, (JSONArray) tvalue, (JSONArray) rvalue);
				} else {//其它Object
					tvalue = callback.onParseObject(key, tvalue, rvalue);
				}

				if (tvalue != null) {//可以在target中加上一些不需要客户端传的键值对
					real.put(key, tvalue);
				}
			}

		}

		//解析内容>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>



		Set<String> rkset = real.keySet(); //解析内容并没有改变rkset

		//解析不允许的字段<<<<<<<<<<<<<<<<<<<
		List<String> disallowList = new ArrayList<String>();
		if ("!".equals(disallow)) {//所有非necessary，改成 !necessary 更好
			for (String key : rkset) {//对@key放行，@role,@column,自定义@position等
				if (key != null && key.startsWith("@") == false
						&& necessaryList.contains(key) == false && objKeySet.contains(key) == false) {
					disallowList.add(key);
				}
			}
		} else {
			String[] disallows = StringUtil.split(disallow);
			if (disallows != null && disallows.length > 0) {
				disallowList.addAll(Arrays.asList(disallows));
			}
		}
		//解析不允许的字段>>>>>>>>>>>>>>>>>>>


		//判断不允许传的key<<<<<<<<<<<<<<<<<<<<<<<<<
		for (String rk : rkset) {
			if (disallowList.contains(rk)) { //不允许的字段
				throw new IllegalArgumentException(name
						+ " 里面不允许传 " + rk + " 等" + StringUtil.getString(disallowList) + "内的任何字段！");
			}

			if (rk == null) { //无效的key
				real.remove(rk);
				continue;
			}

			//不在target内的 key:{}
			if (rk.startsWith("@") == false && objKeySet.contains(rk) == false && real.get(rk) instanceof JSONObject) {
				throw new UnsupportedOperationException(name + " 里面不允许传 " + rk + ":{} ！");
			}
		}
		//判断不允许传的key>>>>>>>>>>>>>>>>>>>>>>>>>



		//校验与修改Request<<<<<<<<<<<<<<<<<
		//在tableKeySet校验后操作，避免 导致put/add进去的Table 被当成原Request的内容
		real = operate(VERIFY, verify, real, creator);
		real = operate(ADD, add, real, creator);
		real = operate(PUT, put, real, creator);
		real = operate(REPLACE, replace, real, creator);
		//校验与修改Request>>>>>>>>>>>>>>>>>

		//TODO放在operate前？考虑性能、operate修改后再验证的值是否和原来一样
		//校验重复<<<<<<<<<<<<<<<<<<<
		String[] uniques = StringUtil.split(unique);
		if (uniques != null && uniques.length > 0) {
			long exceptId = real.getLongValue(KEY_ID);
			for (String u : uniques) {
				verifyRepeat(name, u, real.get(u), exceptId);
			}
		}
		//校验重复>>>>>>>>>>>>>>>>>>>

		Log.i(TAG, "parse  return real = " + JSON.toJSONString(real));
		return real;
	}



	/**执行操作
	 * @param opt
	 * @param targetChild
	 * @param real
	 * @param creator 
	 * @return
	 * @throws Exception
	 */
	private static JSONObject operate(Operation opt, JSONObject targetChild, JSONObject real, SQLCreator creator) throws Exception {
		if (targetChild == null) {
			return real;
		}
		if (real == null) {
			throw new IllegalArgumentException("operate  real == null!!!");
		}


		Set<Entry<String, Object>> set = new LinkedHashSet<>(targetChild.entrySet());
		String tk;
		Object tv;

		for (Entry<String, Object> e : set) {
			tk = e == null ? null : e.getKey();
			if (tk == null) {
				continue;
			}
			tv = e.getValue();

			if (opt == VERIFY) {
				verify(tk, tv, real, creator);
			} else if (opt == PUT) {
				real.put(tk, tv);
			} else {
				if (real.containsKey(tk)) {
					if (opt == REPLACE) {
						real.put(tk, tv);
					}
				} else {
					if (opt == ADD) {
						real.put(tk, tv);
					}
				}
			}
		}

		return real;
	}


	/**验证值
	 * @param tk
	 * @param tv
	 * @param real
	 * @param creator 
	 * @throws Exception
	 */
	private static void verify(@NotNull String tk, @NotNull Object tv, @NotNull JSONObject real, SQLCreator creator) throws Exception {
		if (tv == null) {
			throw new IllegalArgumentException("operate  operate == VERIFY " + tk + ":" + tv + " ,  >> tv == null!!!");
		}

		String rk;
		Object rv;
		Logic logic;
		if (tk.endsWith("$")) { //搜索
			sqlVerify("$", real, tk, tv, creator);
		}
		else if (tk.endsWith("?")) { //正则表达式
			logic = new Logic(tk.substring(0, tk.length() - 1));
			rk = logic.getKey();
			rv = real.get(rk);
			if (rv == null) {
				return;
			}

			JSONArray array = AbstractSQLConfig.newJSONArray(tv);

			boolean m;
			boolean isOr = false;
			Pattern reg;
			for (Object r : array) {
				if (r instanceof String == false) {
					throw new UnsupportedDataTypeException(rk + ":" + rv + " 中value只支持 String 或 [String] 类型！");
				}
				reg = AbstractObjectParser.COMPILE_MAP.get(r);
				if (reg == null) {
					reg = Pattern.compile((String) r);
				}
				m = reg.matcher("" + rv).matches();
				if (m) {
					if (logic.isNot()) {
						throw new IllegalArgumentException(rk + ":value 中value不合法！必须匹配 " + tk + ":" + tv + " !");
					}
					if (logic.isOr()) {
						isOr = true;
						break;
					}
				} else {
					if (logic.isAnd()) {
						throw new IllegalArgumentException(rk + ":value 中value不合法！必须匹配 " + tk + ":" + tv + " !");
					}
				}
			}

			if (isOr == false && logic.isOr()) {
				throw new IllegalArgumentException(rk + ":value 中value不合法！必须匹配 " + tk + ":" + tv + " !");
			}
		} 
		else if (tk.endsWith("{}")) { //rv符合tv条件或在tv内
			if (tv instanceof String) {//TODO  >= 0, < 10
				sqlVerify("{}", real, tk, tv, creator);
			} 
			else if (tv instanceof JSONArray) {
				logic = new Logic(tk.substring(0, tk.length() - 2));
				rk = logic.getKey();
				rv = real.get(rk);
				if (rv == null) {
					return;
				}

				if (((JSONArray) tv).contains(rv) == logic.isNot()) {
					throw new IllegalArgumentException(rk + ":value 中value不合法！必须匹配 " + tk + ":" + tv + " !");
				}
			} 
			else {
				throw new UnsupportedDataTypeException("服务器Request表verify配置错误！");
			}
		}
		else if (tk.endsWith("<>")) { //rv包含tv内的值
			logic = new Logic(tk.substring(0, tk.length() - 2));
			rk = logic.getKey();
			rv = real.get(rk);
			if (rv == null) {
				return;
			}

			if (rv instanceof JSONArray == false) {
				throw new UnsupportedDataTypeException("服务器Request表verify配置错误！");
			}

			JSONArray array = AbstractSQLConfig.newJSONArray(tv);

			boolean isOr = false;
			for (Object o : array) {
				if (((JSONArray) rv).contains(o)) {
					if (logic.isNot()) {
						throw new IllegalArgumentException(rk + ":value 中value不合法！必须匹配 " + tk + ":" + tv + " !");
					}
					if (logic.isOr()) {
						isOr = true;
						break;
					}
				} else {
					if (logic.isAnd()) {
						throw new IllegalArgumentException(rk + ":value 中value不合法！必须匹配 " + tk + ":" + tv + " !");
					}
				}
			}

			if (isOr == false && logic.isOr()) {
				throw new IllegalArgumentException(rk + ":value 中value不合法！必须匹配 " + tk + ":" + tv + " !");
			}
		}
		else {
			throw new IllegalArgumentException("服务器Request表verify配置错误！");
		}
	}

	/**通过数据库执行SQL语句来验证条件
	 * @param funChar
	 * @param real
	 * @param tk
	 * @param tv
	 * @param creator
	 * @throws Exception
	 */
	private static void sqlVerify(@NotNull String funChar, @NotNull JSONObject real, @NotNull String tk, @NotNull Object tv
			, @NotNull SQLCreator creator) throws Exception {
		//不能用Parser, 0 这种不符合 StringUtil.isName !
		Logic logic = new Logic(tk.substring(0, tk.length() - funChar.length()));
		String rk = logic.getKey();
		Object rv = real.get(rk);
		if (rv == null) {
			return;
		}

		SQLConfig config = creator.createSQLConfig().setMethod(RequestMethod.HEAD).setCount(1).setPage(0);
		config.setTable(Test.class.getSimpleName());
		config.setTest(true);
		config.putWhere("'" + rv + "'" + logic.getChar() + funChar, tv);

		SQLExecutor executor = creator.createSQLExecutor();
		JSONObject result = null;
		try {
			result = executor.execute(config);
		} catch (Exception e) {
			throw e;
		} finally {
			executor.close();
		}
		if (result != null && JSONResponse.isExist(result.getIntValue(JSONResponse.KEY_COUNT)) == false) {
			throw new IllegalArgumentException(rk + ":" + rv + "中value不合法！必须匹配 " + logic.getChar() + tv + " ！");
		}		
	}


	/**验证是否重复
	 * @param table
	 * @param key
	 * @param value
	 * @throws Exception
	 */
	public static void verifyRepeat(String table, String key, Object value) throws Exception {
		verifyRepeat(table, key, value, 0);
	}
	/**验证是否重复
	 * @param table
	 * @param key
	 * @param value
	 * @param exceptId 不包含id
	 * @throws Exception
	 */
	public static void verifyRepeat(String table, String key, Object value, long exceptId) throws Exception {
		if (key == null || value == null) {
			Log.e(TAG, "verifyRepeat  key == null || value == null >> return;");
			return;
		}
		if (value instanceof JSON) {
			throw new UnsupportedDataTypeException(key + ":value 中value的类型不能为JSON！");
		}

		JSONRequest request = new JSONRequest(key, value);
		if (exceptId > 0) {//允许修改自己的属性为该属性原来的值
			request.put(JSONRequest.KEY_ID + "!", exceptId);
		}
		//		JSONObject repeat = new AbstractParser(HEAD, true).parseResponse(
		//				new JSONRequest(table, request)
		//				);
		//		repeat = repeat == null ? null : repeat.getJSONObject(table);
		//		if (repeat == null) {
		//			throw new Exception("服务器内部错误  verifyRepeat  repeat == null");
		//		}
		//		if (repeat.getIntValue(JSONResponse.KEY_COUNT) > 0) {
		//			throw new ConflictException(key + ": " + value + " 已经存在，不能重复！");
		//		}
	}


}
