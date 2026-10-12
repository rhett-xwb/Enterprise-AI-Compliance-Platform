import { createRouter, createWebHashHistory } from "vue-router";
import { session } from "../auth/session";
import { loadIdentity } from "../api/client";
import { features, canAccess } from "./access";
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: "/login",
      component: () => import("../views/LoginView.vue"),
      meta: { title: "登录", public: true },
    },
    {
      path: "/",
      component: () => import("../layouts/WorkspaceLayout.vue"),
      children: [
        {
          path: "",
          component: () => import("../views/DashboardView.vue"),
          meta: { title: "工作台" },
        },
        ...features.map((feature) => ({
          path: feature.path.slice(1),
          component:
            feature.path === "/document-tasks"
              ? () => import("../views/DocumentTasksView.vue")
              :
            feature.path === "/approvals"
              ? () => import("../views/ApprovalsView.vue")
              : feature.path === "/workflows"
              ? () => import("../views/WorkflowsView.vue")
              :
            feature.path === "/requests"
              ? () => import("../views/RequestsView.vue")
              :
            feature.path === "/questions"
              ? () => import("../views/QuestionsView.vue")
              :
            feature.path === "/retrieval"
              ? () => import("../views/RetrievalView.vue")
              :
            feature.path === "/users"
              ? () => import("../views/UsersView.vue")
              : ["/documents", "/document-management"].includes(feature.path)
                ? () => import("../views/DocumentsView.vue")
                : () => import("../views/FeatureView.vue"),
          meta: feature,
        })),
        {
          path: "forbidden",
          component: () => import("../views/ForbiddenView.vue"),
          meta: { title: "无访问权限" },
        },
      ],
    },
    {
      path: "/:pathMatch(.*)*",
      component: () => import("../views/NotFoundView.vue"),
      meta: { title: "页面不存在" },
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
});
export function safeRedirect(value) {
  return typeof value === "string" &&
    (value === "/" || features.some((f) => f.path === value))
    ? value
    : "/";
}
router.beforeEach(async (to) => {
  if (to.meta.public) return true;
  if (!session.token) return { path: "/login", query: { redirect: to.path } };
  try {
    const user = await loadIdentity(true);
    if (!user) return "/login";
    if (!canAccess(user, to.meta)) return "/forbidden";
  } catch (error) {
    return {
      path: "/login",
      query: {
        redirect: to.path,
        reason: error.response?.status === 401 ? "expired" : "connection",
      },
    };
  }
});
router.afterEach((to) => {
  document.title = `${to.meta.title || "工作台"} | 企业合规平台`;
});
window.addEventListener("session-expired", () => {
  if (router.currentRoute.value.path !== "/login")
    router.replace({ path: "/login", query: { reason: "expired" } });
});
export default router;
