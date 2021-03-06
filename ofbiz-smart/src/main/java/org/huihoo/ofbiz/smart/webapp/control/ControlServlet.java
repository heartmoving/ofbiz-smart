/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *******************************************************************************/
package org.huihoo.ofbiz.smart.webapp.control;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.ParserConfigurationException;

import org.huihoo.ofbiz.smart.base.C;
import org.huihoo.ofbiz.smart.base.auth.ILoginAuth;
import org.huihoo.ofbiz.smart.base.utils.CommUtils;
import org.huihoo.ofbiz.smart.base.utils.Debug;
import org.huihoo.ofbiz.smart.entity.Delegator;
import org.huihoo.ofbiz.smart.entity.EbeanDelegator;
import org.huihoo.ofbiz.smart.entity.GenericEntityException;
import org.huihoo.ofbiz.smart.service.GenericServiceException;
import org.huihoo.ofbiz.smart.service.ServiceDispatcher;
import org.huihoo.ofbiz.smart.webapp.control.ConfigXMLLoader.Action;
import org.huihoo.ofbiz.smart.webapp.control.ConfigXMLLoader.ActionMap;
import org.huihoo.ofbiz.smart.webapp.control.ConfigXMLLoader.Event;
import org.huihoo.ofbiz.smart.webapp.control.ConfigXMLLoader.Response;
import org.xml.sax.SAXException;


public class ControlServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final String module = ControlServlet.class.getName();
  private final RequestHandler requestHandler = new RequestHandler();
  private volatile ActionMap actionMap;
  private volatile ServiceDispatcher serviceDispatcher;
  private volatile Delegator prmaryDelegator;
  private volatile Properties appConfigProp;
  private volatile ILoginAuth loginAuth;

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
          IOException {
    this.doPost(req, resp);
  }


  @Override
  public void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
          IOException {
    // 开发模式下，每次读取映射配置文件
    if (C.ENV_MODE_DEVELOP.equals(actionMap.envMode)) {
      ConfigXMLLoader.actionMap.actions.clear();
      try {
        ConfigXMLLoader.loadXmlConfig(null);
        actionMap = ConfigXMLLoader.actionMap;
        serviceDispatcher = new ServiceDispatcher(prmaryDelegator, serviceResourceName);
      } catch (GenericServiceException | ParserConfigurationException | SAXException e) {
        Debug.logError(e, "实例化ServiceDispatcher发生异常", module);
      }
    }

    // 注意： ActionMap的实例是克隆的。因为，RequestHandler会动态修改ActionMap
    ActionMap cloneActionMap = (ActionMap) actionMap.clone();

    req.setAttribute("ctxPath", req.getContextPath());

    // 将Delegator和ServiceDispatcher保存在请求上下文中，便于RequestHandler使用
    req.setAttribute(C.CTX_DELEGATOR, prmaryDelegator);
    req.setAttribute(C.CTX_SERVICE_DISPATCHER, serviceDispatcher);
    req.setAttribute(C.CTX_APP_CONFIG_PROP, appConfigProp);
    req.setAttribute(C.API_LOGIN_AUTH_KEY, loginAuth);
    req.setAttribute("homePage", homePage);
    // 如果配置了API服务路由网关入口，增加一个单独的Action,专门处理API请求
    if (CommUtils.isNotEmpty(apiRouterGateway)) {
      Action apiAction = new Action();
      apiAction.uri = apiRouterGateway;
      apiAction.allowMethod = "all";
      apiAction.event = new Event();
      apiAction.event.type = C.EVENT_API;
      apiAction.response = new Response();
      apiAction.response.type = C.RESP_JSON;
      cloneActionMap.actions.add(apiAction);
    }
    requestHandler.handleRequest(req, resp, cloneActionMap);
  }

  private String serviceResourceName;
  private String apiRouterGateway;
  private String homePage;

  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);
    try {
      ConfigXMLLoader.loadXmlConfig(null);
      actionMap = ConfigXMLLoader.actionMap;
      String appConfigFile = config.getInitParameter("app-config-file");
      apiRouterGateway = config.getInitParameter("api-router-gateway");
      homePage = config.getInitParameter("home-page"); //首页地址
      appConfigProp = new Properties();

      ClassLoader cL = Thread.currentThread().getContextClassLoader();
      InputStream is = cL.getResourceAsStream(appConfigFile);

      if (is == null) {// 针对Mock测试时的处理
        URL url = Thread.currentThread().getContextClassLoader().getResource("");
        if ("file".equals(url.getProtocol())) {
          String path = url.getPath();
          int tIdx = path.indexOf("target");
          if (tIdx != -1) path = path.substring(0, tIdx + "/target".length());

          appConfigProp.load(new FileInputStream(new File(path + "/classes/" + appConfigFile)));
        }
      } else {
        appConfigProp.load(is);
      }

      String primaryDatasourceName = appConfigProp.getProperty("datasource.default");
      serviceResourceName = appConfigProp.getProperty("service.resource.name");
      String entityBasePackage = appConfigProp.getProperty("entity.base.package");

      prmaryDelegator = new EbeanDelegator(primaryDatasourceName, entityBasePackage, appConfigProp);
      serviceDispatcher = new ServiceDispatcher(prmaryDelegator, serviceResourceName);
      // 将Delegator和ServiceDispatcher和请求路径后缀保存在服务器全局上下文中
      ServletContext servletContext = config.getServletContext();

      servletContext.setAttribute(C.ACTION_URI_SUFFIX, actionMap.actionUriSuffix);
      servletContext.setAttribute(C.CTX_DELEGATOR, prmaryDelegator);
      servletContext.setAttribute(C.CTX_SERVICE_DISPATCHER, serviceDispatcher);
      servletContext.setAttribute(C.CTX_APP_CONFIG_PROP, appConfigProp);
      /**登录认证class**/
      String apiLoginAuth = config.getInitParameter("api-login-auth");
      /**为null使用默认的**/
      if(null == apiLoginAuth || "".equals(apiLoginAuth)){
    	  apiLoginAuth = "org.huihoo.ofbiz.smart.base.auth.impl.LoginAuth"; 
      }
      Object ob = Class.forName(apiLoginAuth).newInstance();
      if(ob instanceof ILoginAuth){
    	  loginAuth = (ILoginAuth)ob;
    	  servletContext.setAttribute(C.API_LOGIN_AUTH_KEY, ob);
      }else{
    	  throw new ServletException("API login authentication org.huihoo.ofbiz.smart.base.auth.impl.LoginAuth is not inherited");
      }
    } catch (GenericServiceException e) {
      throw new ServletException(e);
    } catch (GenericEntityException e) {
      throw new ServletException(e);
    } catch (IOException e) {
      throw new ServletException(e);
    } catch (ParserConfigurationException e) {
      throw new ServletException(e);
    } catch (SAXException e) {
      throw new ServletException(e);
    } catch (InstantiationException e) {
      throw new ServletException(e);
	} catch (IllegalAccessException e) {
	  throw new ServletException(e);
	} catch (ClassNotFoundException e) {
	  throw new ServletException(e);
	}catch(Exception e){
	  throw new ServletException(e);
	}
  }



}
