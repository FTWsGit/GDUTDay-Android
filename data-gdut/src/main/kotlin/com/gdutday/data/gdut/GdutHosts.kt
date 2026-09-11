package com.gdutday.data.gdut

// 各系统地址配置已下沉到 core-model（分层修复 M21：core-datastore / core-network 是
// core 叶子，不能反向依赖协议层 data-gdut）。这里保留 typealias 与协议文档常量的
// 交叉校验；新代码请直接 import com.gdutday.core.model.GdutHosts。

public typealias GdutHosts = com.gdutday.core.model.GdutHosts
