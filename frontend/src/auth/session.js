import { reactive } from "vue";
const key = "compliance.accessToken";
export const session = reactive({
  token: sessionStorage.getItem(key),
  user: null,
});
export function setToken(token) {
  session.token = token;
  session.user = null;
  if (token) sessionStorage.setItem(key, token);
  else sessionStorage.removeItem(key);
}
export function clearSession() {
  setToken(null);
}
