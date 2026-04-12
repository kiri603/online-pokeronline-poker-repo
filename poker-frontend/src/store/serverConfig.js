const TAB_AUTH_TOKEN_KEY = "poker:tab-auth-token";

export const getServerHost = () => {
  const currentHost = window.location.hostname;
  return `${currentHost}:8080`;
};

export const getHttpBaseUrl = () => `http://${getServerHost()}`;

export const getWsBaseUrl = () => `ws://${getServerHost()}`;

export const getTabAuthToken = () => {
  if (typeof window === "undefined") {
    return "";
  }
  return window.sessionStorage.getItem(TAB_AUTH_TOKEN_KEY) || "";
};

export const setTabAuthToken = (token) => {
  if (typeof window === "undefined") {
    return;
  }
  if (!token) {
    window.sessionStorage.removeItem(TAB_AUTH_TOKEN_KEY);
    return;
  }
  window.sessionStorage.setItem(TAB_AUTH_TOKEN_KEY, token);
};

export const clearTabAuthToken = () => {
  if (typeof window === "undefined") {
    return;
  }
  window.sessionStorage.removeItem(TAB_AUTH_TOKEN_KEY);
};

export const apiFetch = async (path, options = {}) => {
  const tabToken = getTabAuthToken();
  const response = await fetch(`${getHttpBaseUrl()}${path}`, {
    credentials: "include",
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(tabToken ? { "X-Poker-Auth-Token": tabToken } : {}),
      ...(options.headers || {}),
    },
  });
  return response;
};
