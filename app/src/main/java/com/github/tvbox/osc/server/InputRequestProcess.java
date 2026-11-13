package com.github.tvbox.osc.server;

import com.google.gson.JsonObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * @author pj567
 * @date :2021/1/5
 * @description: 响应按键和输入
 */

public class InputRequestProcess implements RequestProcess {
    private RemoteServer remoteServer;

    public InputRequestProcess(RemoteServer remoteServer) {
        this.remoteServer = remoteServer;
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        if (session.getMethod() == NanoHTTPD.Method.POST) {
            switch (fileName) {
                case "/action":
                    return true;
            }
        }
        return false;
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        DataReceiver mDataReceiver = remoteServer.getDataReceiver();
        switch (fileName) {
            case "/action":
                if (params != null && params.get("do") != null) {
                    String action = params.get("do");

                    switch (action) {
                        case "inputField": {
                            RemoteInputRequest request = RemoteInputRequest.from(params);
                            if (request == null) {
                                return fieldResponse(NanoHTTPD.Response.Status.BAD_REQUEST, false,
                                        "", "invalid", "请输入有效的字段和值");
                            }
                            if (mDataReceiver == null) {
                                return fieldResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, false,
                                        request.target, "unavailable", "软件输入服务暂不可用");
                            }
                            boolean filled = mDataReceiver.onInputFieldReceived(request);
                            return fieldResponse(NanoHTTPD.Response.Status.OK, true, request.target,
                                    filled ? "filled" : "queued",
                                    filled ? "已填入对应输入框，请在软件中手动确认保存"
                                            : "已发送，打开对应设置框填入后请手动确认保存");
                        }
                        case "search": {
                            if (mDataReceiver == null) {
                                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "input receiver unavailable");
                            }
                            mDataReceiver.onTextReceived(params.get("word").trim());
                            break;
                        }
                        case "api": {
                            if (mDataReceiver == null) {
                                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "configuration receiver unavailable");
                            }
                            ApiConfigRequest request = ApiConfigRequest.from(params);
                            if (!request.hasAnyChange()) {
                                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "at least one address is required");
                            }
                            mDataReceiver.onApiReceived(request.subscriptionUrl, request.liveUrl,
                                    request.hasSubscriptionChange, request.hasLiveChange);
                            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
                        }
                        case "push": {
                            if (mDataReceiver == null) {
                                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "input receiver unavailable");
                            }
                            // 暂未实现
                            mDataReceiver.onPushReceived(params.get("url").trim());
                            break;
                        }
                    }
                }
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok");
            default:
                return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.");
        }
    }

    private static NanoHTTPD.Response fieldResponse(NanoHTTPD.Response.IStatus status,
                                                    boolean success, String target,
                                                    String state, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", success);
        result.addProperty("target", target);
        result.addProperty("state", state);
        result.addProperty("updated", "filled".equals(state));
        result.addProperty("saved", false);
        result.addProperty("message", message);
        return RemoteServer.createJSONResponse(status, result.toString());
    }
}
