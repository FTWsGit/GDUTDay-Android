package com.gdutday.data.gdut.session

// 会话模型已下沉到 core-model（分层修复 M21：core-datastore / core-network 是 core 叶子，
// 不能反向依赖协议层 data-gdut）。这里保留 typealias，存量 import 无需改动；
// 新代码请直接 import com.gdutday.core.model.*。

public typealias GdutSession = com.gdutday.core.model.GdutSession

public typealias LoginMethod = com.gdutday.core.model.LoginMethod
