import axios from "axios";
import { ElMessage } from "element-plus";
import { session, clearSession } from "../auth/session";
export const api = axios.create({ baseURL: "/api", timeout: 15000 });
api.interceptors.request.use((config) => {
  config.sessionToken = session.token;
  if (session.token) config.headers.Authorization = `Bearer ${session.token}`;
  return config;
});
api.interceptors.response.use(
  (response) => {
    if (response.config.responseType === "blob") return response;
    if (response.data.code !== 0)
      return Promise.reject(new Error(response.data.message || "请求失败"));
    return response.data.data;
  },
  async (error) => {
    if (error.response?.data instanceof Blob) {
      try {
        error.response.data = JSON.parse(await error.response.data.text());
      } catch {}
    }
    const status = error.response?.status;
    const isLogin = error.config?.url === "/auth/login";
    if (
      status === 401 &&
      !isLogin &&
      error.config?.sessionToken === session.token
    ) {
      clearSession();
      window.dispatchEvent(new Event("session-expired"));
    }
    error.userMessage =
      status === 401
        ? isLogin
          ? "账号或密码错误，或账号已停用"
          : "登录已失效，请重新登录"
        : status === 403
          ? "你没有权限执行此操作"
          : status === 404
            ? "记录不存在或你没有访问权限"
            : !error.response
              ? "无法连接服务器，请检查网络或稍后重试"
              : error.response.data?.message || "服务器处理失败，请稍后重试";
    if (!error.config?.quiet) ElMessage.error(error.userMessage);
    return Promise.reject(error);
  },
);
let identityRequest;
export async function loadIdentity(force = false) {
  if (!session.token) return null;
  if (!force && session.user) return session.user;
  const token = session.token;
  if (identityRequest?.token === token) return identityRequest.promise;
  const promise = api
    .get("/auth/me", { quiet: true })
    .then((user) => {
      if (token === session.token) session.user = user;
      return token === session.token ? user : null;
    })
    .finally(() => {
      if (identityRequest?.promise === promise) identityRequest = null;
    });
  identityRequest = { token, promise };
  return promise;
}
