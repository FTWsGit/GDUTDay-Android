package com.gdutday.core.model

/** 课程条目的来源。决定它能否被用户编辑、以及在同步时是否会被覆盖。 */
public enum class CourseSource {
    /** 教务系统同步来的。每次同步会被整体替换。 */
    SCHOOL,

    /** 用户手动添加的（社团活动、实验、选修旁听等）。同步时**不会**被删除。 */
    CUSTOM,

    /** 由考试安排生成的占位条目。 */
    EXAM,

    /**
     * 用户对教务课程的修改，以补丁形式存在。
     * 同步时**不会**被删除，并在每次同步后按 [com.gdutday.core.model.Course.overrideTargetNaturalKey]
     * 重新覆盖到同步结果上（见 data-repository 的 `applyUserOverrides`）。
     */
    OVERRIDE,
}
