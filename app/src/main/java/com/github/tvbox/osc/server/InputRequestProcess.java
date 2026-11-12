package com.github.tvbox.osc.server;

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
}
