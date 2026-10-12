export const features = [
  { path: '/document-tasks', title: '文档处理任务', available: true, role: 'ADMIN', stage: 10, description: '查看异步处理状态与故障，重新排队失败任务。' },
  {
    path: "/questions",
    title: "制度问答",
    available: true,
    permission: "document:read",
    stage: 7,
    description: "依据授权制度回答问题，核对引用与历史版本。",
  },
  {
    path: "/retrieval",
    title: "制度原文检索",
    available: true,
    permission: "document:read",
    stage: 6,
    description: "检索当前授权的有效制度，查看原文和来源。",
  },
  {
    path: "/users",
    title: "用户管理",
    permission: "user:manage",
    role: "ADMIN",
  },
  {
    path: "/documents",
    title: "制度查询",
    available: true,
    permission: "document:read",
    stage: 4,
    description: "查看适用制度和有效版本，核对制度来源。",
  },
  {
    path: "/document-management",
    title: "制度管理",
    available: true,
    permission: "document:manage",
    stage: 4,
    description: "上传制度文件，管理版本与发布状态。",
  },
  {
    path: "/requests",
    available: true,
    title: "我的申请",
    permission: "approval:submit",
    stage: 8,
    description: "保存申请草稿，查看预审建议和审批结果。",
  },
  {
    path: "/approvals",
    available: true,
    title: "审批中心",
    permission: "approval:review",
    stage: 9,
    description: "处理分配给你的审批待办并查看操作记录。",
  },
  {
    path: "/workflows",
    title: "审批流程配置",
    available: true,
    role: "ADMIN",
    stage: 9,
    description: "配置差旅金额区间、审批节点与审批人，发布不可变流程版本。",
  },
  {
    path: "/contracts",
    title: "合同审查",
    permission: "contract:review",
    description: "在核心申请审批流程完成后开放合同审查。",
  },
];
export const roleNames = {
  ADMIN: "系统管理员",
  LEGAL: "法务与合规人员",
  EMPLOYEE: "普通员工",
};
export function canAccess(user, feature) {
  return (
    !!user &&
    (!feature.role || user.roles.includes(feature.role)) &&
    (!feature.permission || user.permissions.includes(feature.permission))
  );
}
export function visibleFeatures(user) {
  return features.filter((feature) => canAccess(user, feature));
}
