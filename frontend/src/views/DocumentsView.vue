<script setup>
import { computed, reactive, ref, watch, onUnmounted } from "vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { api } from "../api/client";
import { roleNames } from "../router/access";
const route = useRoute();
const management = computed(() => route.path === "/document-management");
const rows = ref([]),
  total = ref(0),
  page = ref(1),
  loading = ref(false),
  error = ref("");
const selected = ref(null),
  audits = ref([]),
  detailLoading = ref(false),
  detailError = ref("");
const saving = ref(false),
  dialog = ref(false),
  mode = ref("create"),
  formError = ref(""),
  chosenFile = ref(null);
const form = reactive({
  title: "",
  roles: ["EMPLOYEE", "LEGAL", "ADMIN"],
  departments: "",
  versionNo: "",
  effectiveAt: "",
  expiresAt: "",
});
const statuses = {
  DRAFT: "草稿",
  PUBLISHED: "已发布",
  RETIRED: "已停用",
  PENDING: "待处理",
  PROCESSING: "处理中",
  READY: "处理就绪",
  FAILED: "处理失败",
  PARSED: "已解析，待索引",
};
const actions = {
  ASYNC_RETRY: "重新排队任务",
  INDEX_REQUEST: "请求索引",
  INDEX_COMPLETE: "索引完成",
  CREATE: "创建制度",
  UPLOAD: "上传版本",
  UPDATE_SCOPE: "修改名称与范围",
  PUBLISH: "发布版本",
  RETIRE: "停用版本",
  DELETE: "删除制度",
  PARSE_REQUEST: "请求解析",
  PARSE_RETRY: "重试解析",
};
const parseDialog = ref(false), parseVersion = ref(null), parseTask = ref(null),
  parseSources = ref([]), parseError = ref(""), parseLoading = ref(false);
const taskNames = { PENDING: "排队中", PROCESSING: "解析中", SUCCEEDED: "解析完成", FAILED: "解析失败" };
const parseErrors = {
  PARSE_TIMEOUT: "解析超时，请重试或减少文件页数", WORKER_TIMEOUT: "任务中断或超时，可以重试",
  EMPTY_DOCUMENT: "未提取到文字，请检查扫描质量", PAGE_LIMIT: "文件超过 100 页，请拆分后上传",
  FILE_INTEGRITY: "原文件校验失败，请联系管理员", PARSER_UNAVAILABLE: "解析服务连接失败，请稍后重试",
  PARSER_NOT_CONFIGURED: "解析服务尚未配置", PARSER_BUSY: "解析服务繁忙，请稍后重试",
  PDF_ENCRYPTED: "请上传未加密的 PDF", PARSE_FAILED: "文件无法解析，请检查后重试",
  PARSER_RESPONSE_INVALID: "解析结果校验失败，请联系管理员",
};
let parseTimer, parseSequence = 0;
function closeParse() {
  clearTimeout(parseTimer); ++parseSequence;
  parseDialog.value = false; parseSources.value = []; parseTask.value = null; parseVersion.value = null;
}
async function refreshParse(sequence, docId, versionId) {
  try {
    const task = await api.get(`/documents/${docId}/versions/${versionId}/parse`, { quiet: true });
    if (sequence !== parseSequence) return;
    parseTask.value = task;
    const row = selected.value?.versions.find((v) => v.id === versionId);
    if (row && task && task.status !== 'SUCCEEDED') row.processingStatus = { FAILED: 'FAILED', PROCESSING: 'PROCESSING', PENDING: 'PENDING' }[task.status];
    if (task?.status === "SUCCEEDED") {
      const current = await api.get(`/documents/${docId}`, { quiet: true });
      if (sequence !== parseSequence) return;
      if (row) row.processingStatus = current.versions.find((v) => v.id === versionId)?.processingStatus || row.processingStatus;
      const sources = await api.get(`/documents/${docId}/versions/${versionId}/sources`, { quiet: true });
      if (sequence === parseSequence) parseSources.value = sources;
    } else if (task && ["PENDING", "PROCESSING"].includes(task.status)) {
      parseTimer = setTimeout(() => refreshParse(sequence, docId, versionId), 2000);
    }
  } catch (e) { if (sequence === parseSequence) parseError.value = e.userMessage || "解析状态加载失败"; }
  finally { if (sequence === parseSequence) parseLoading.value = false; }
}
async function openParse(row, start = false) {
  closeParse();
  const sequence = ++parseSequence, docId = selected.value.document.id;
  parseDialog.value = true; parseVersion.value = row; parseError.value = ""; parseLoading.value = true;
  try {
    if (start) await api.post(`/documents/${docId}/versions/${row.id}/parse`, null, {
      params: { retry: row.processingStatus === "FAILED" }, quiet: true,
    });
    if (sequence === parseSequence) await refreshParse(sequence, docId, row.id);
  } catch (e) {
    if (sequence === parseSequence) { parseError.value = e.userMessage || "解析请求失败"; parseLoading.value = false; }
  }
}
onUnmounted(closeParse);
const indexDialog = ref(false), indexVersion = ref(null), indexState = ref(null), indexError = ref(""), indexing = ref(false);
let indexSequence = 0, indexTimer;
async function openIndex(row, start = false, rebuild = false) {
  clearTimeout(indexTimer);
  const sequence = ++indexSequence, docId = selected.value.document.id;
  indexVersion.value = row; indexState.value = null; indexError.value = ""; indexDialog.value = true; indexing.value = true;
  try {
    const url = `/documents/${docId}/versions/${row.id}/index`;
    const result = start
      ? await api.post(url, null, { params: { rebuild }, quiet: true, timeout: 195000 })
      : await api.get(url, { quiet: true });
    if (sequence !== indexSequence) return;
    indexState.value = result;
    await openDetail(docId);
    if (sequence === indexSequence && result?.status === 'PROCESSING') indexTimer = setTimeout(() => openIndex(row), 2000);
  } catch (e) {
    if (sequence === indexSequence) indexError.value = e.userMessage || "索引请求失败，请刷新状态后重试";
  } finally { if (sequence === indexSequence) indexing.value = false; }
}
function closeIndex() { clearTimeout(indexTimer); ++indexSequence; indexing.value = false; indexState.value = null; indexVersion.value = null; indexError.value = ""; }
onUnmounted(closeIndex);
let listSequence = 0,
  detailSequence = 0;
async function load() {
  const sequence = ++listSequence;
  loading.value = true;
  error.value = "";
  try {
    const result = await api.get("/documents", {
      params: { management: management.value, page: page.value, size: 20 },
      quiet: true,
    });
    if (sequence === listSequence) {
      rows.value = result.records;
      total.value = result.total;
    }
  } catch (e) {
    if (sequence === listSequence) {
      rows.value = [];
      error.value = e.userMessage || "加载失败";
    }
  } finally {
    if (sequence === listSequence) loading.value = false;
  }
}
async function openDetail(id) {
  const sequence = ++detailSequence;
  detailLoading.value = true;
  detailError.value = "";
  audits.value = [];
  selected.value = null;
  try {
    const result = await api.get(`/documents/${id}`, { quiet: true });
    if (sequence !== detailSequence) return;
    selected.value = result;
    if (management.value && result.manageable) {
      const history = await api.get(`/documents/${id}/audit`, { quiet: true });
      if (sequence === detailSequence) audits.value = history;
    }
  } catch (e) {
    if (sequence === detailSequence)
      detailError.value = e.userMessage || "详情加载失败";
  } finally {
    if (sequence === detailSequence) detailLoading.value = false;
  }
}
function openForm(nextMode) {
  mode.value = nextMode;
  formError.value = "";
  chosenFile.value = null;
  const source = nextMode === "create" ? null : selected.value;
  Object.assign(form, {
    title: source?.document.title || "",
    roles: [...(source?.roles || ["EMPLOYEE", "LEGAL", "ADMIN"])],
    departments: source?.departmentIds.join(",") || "",
    versionNo: "",
    effectiveAt: "",
    expiresAt: "",
  });
  dialog.value = true;
}
function pickFile(event) {
  chosenFile.value = event.target.files[0] || null;
}
function metadata() {
  const tokens = form.departments.trim()
    ? form.departments.split(",").map((v) => v.trim())
    : [];
  if (
    tokens.some(
      (v) => !/^[1-9][0-9]*$/.test(v) || !Number.isSafeInteger(Number(v)),
    )
  )
    throw new Error("部门编号必须是逗号分隔的正整数");
  return {
    title: form.title.trim(),
    roles: form.roles,
    departmentIds: tokens.map(Number),
  };
}
async function save() {
  if (saving.value) return;
  saving.value = true;
  formError.value = "";
  try {
    if (mode.value !== "version" && (!form.title.trim() || !form.roles.length))
      throw new Error("请填写制度名称并选择至少一个可读角色");
    let result;
    if (mode.value === "scope")
      result = await api.put(
        `/documents/${selected.value.document.id}`,
        metadata(),
        { quiet: true },
      );
    else {
      if (!/^[a-zA-Z0-9._-]{1,32}$/.test(form.versionNo) || !form.effectiveAt)
        throw new Error("请填写有效的版本号及生效时间");
      if (
        !chosenFile.value ||
        chosenFile.value.size === 0 ||
        chosenFile.value.size > 10 * 1024 * 1024
      )
        throw new Error("请选择非空且不超过 10 MiB 的文件");
      const version = {
        versionNo: form.versionNo,
        effectiveAt: form.effectiveAt,
        expiresAt: form.expiresAt || null,
      };
      const data = new FormData();
      data.append(
        "metadata",
        new Blob(
          [
            JSON.stringify(
              mode.value === "create"
                ? { document: metadata(), version }
                : version,
            ),
          ],
          { type: "application/json" },
        ),
      );
      data.append("file", chosenFile.value);
      result = await api.post(
        mode.value === "create"
          ? "/documents"
          : `/documents/${selected.value.document.id}/versions`,
        data,
        { quiet: true, timeout: 60000 },
      );
    }
    dialog.value = false;
    ElMessage.success(
      mode.value === "scope" ? "制度范围已更新" : "版本已保存为草稿",
    );
    await load();
    await openDetail(result.document.id);
  } catch (e) {
    formError.value = e.userMessage || e.message || "保存失败";
  } finally {
    saving.value = false;
  }
}
async function act(action, version) {
  if (saving.value) return;
  try {
    await ElMessageBox.confirm(
      action === "publish"
        ? "确认发布此版本并停用旧版本？"
        : action === "retire"
          ? "确认停用当前版本？停用后不再用于当前制度查询。"
          : "确认删除制度？原文件和审计记录将保留，但不再开放访问。",
      "确认操作",
      { type: "warning", confirmButtonText: "确认", cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  saving.value = true;
  const id = selected.value.document.id;
  try {
    if (action === "delete") {
      await api.delete(`/documents/${id}`);
      selected.value = null;
      audits.value = [];
    } else {
      await api.post(`/documents/${id}/versions/${version.id}/${action}`);
      await openDetail(id);
    }
    ElMessage.success("操作成功");
    await load();
  } catch {
    /* 公共客户端显示错误，保留当前页面以便重试。 */
  } finally {
    saving.value = false;
  }
}
async function download(version) {
  if (saving.value) return;
  saving.value = true;
  try {
    const response = await api.get(
      `/documents/${selected.value.document.id}/versions/${version.id}/file`,
      { responseType: "blob" },
    );
    const url = URL.createObjectURL(response.data),
      link = document.createElement("a");
    link.href = url;
    link.download = version.originalFilename;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch {
    /* 包括下载时重新校验权限后返回的错误。 */
  } finally {
    saving.value = false;
  }
}
watch(management, () => {
  closeParse();
  closeIndex(); indexDialog.value = false;
  ++detailSequence;
  selected.value = null;
  audits.value = [];
  detailLoading.value = false;
  detailError.value = "";
  dialog.value = false;
  page.value = 1;
  rows.value = [];
  total.value = 0;
  load();
}, { immediate: true });
</script>
<template>
  <div class="page-heading">
    <p class="eyebrow">制度与版本</p>
    <h1>{{ management ? "制度管理" : "制度查询" }}</h1>
    <p class="muted">
      {{
        management
          ? "保存原文件、管理访问范围与版本，并保留发布记录。"
          : "查询你有权访问的当前有效制度，查看版本并下载原文件。"
      }}
    </p>
  </div>
  <section class="panel">
    <div class="panel-toolbar">
      <h2>{{ management ? "可管理制度" : "当前有效制度" }}</h2>
      <div>
        <el-button :disabled="loading || saving" @click="load">刷新</el-button
        ><el-button
          v-if="management"
          type="primary"
          :disabled="saving"
          @click="openForm('create')"
          >上传新制度</el-button
        >
      </div>
    </div>
    <el-skeleton v-if="loading" :rows="5" animated />
    <div v-else-if="error" role="alert">
      <el-alert :title="error" type="error" :closable="false" /><el-button
        class="retry"
        @click="load"
        >重新加载</el-button
      >
    </div>
    <el-empty
      v-else-if="!rows.length"
      :description="
        management
          ? '暂无可管理制度，可上传首个版本'
          : '暂无你有权访问的有效制度'
      "
    />
    <el-table v-else :data="rows"
      ><el-table-column
        prop="title"
        label="制度名称"
        min-width="220"
      /><el-table-column label="当前版本" min-width="130"
        ><template #default="{ row }">{{
          row.currentVersionNo ?? "尚未发布"
        }}</template></el-table-column
      ><el-table-column label="操作" width="100"
        ><template #default="{ row }"
          ><el-button
            link
            type="primary"
            :disabled="saving"
            @click="openDetail(row.id)"
            >查看版本</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <el-pagination
      v-if="total && !error"
      v-model:current-page="page"
      :total="total"
      :page-size="20"
      layout="prev, pager, next, total"
      :disabled="loading"
      @current-change="load"
    />
  </section>
  <section v-if="detailLoading || detailError || selected" class="panel">
    <el-skeleton v-if="detailLoading" :rows="4" animated />
    <el-alert
      v-if="detailError"
      :title="detailError"
      type="error"
      :closable="false"
    />
    <template v-if="selected && !detailLoading"
      ><div class="panel-toolbar">
        <h2>{{ selected.document.title }}</h2>
        <div v-if="management && selected.manageable">
          <el-button :disabled="saving" @click="openForm('version')"
            >上传新版本</el-button
          ><el-button :disabled="saving" @click="openForm('scope')"
            >编辑范围</el-button
          ><el-button
            type="danger"
            plain
            :disabled="saving"
            @click="act('delete')"
            >删除制度</el-button
          >
        </div>
      </div>
      <p class="muted">
        可读角色：{{
          selected.roles.map((r) => roleNames[r] || r).join("、")
        }}；部门范围：{{
          selected.departmentIds.length
            ? selected.departmentIds.join("、")
            : "不限制部门"
        }}。已停用或过期的历史版本仅供追溯。
      </p>
      <el-alert
        v-if="management"
        title="新上传版本先保存为草稿，完成解析和索引后才能发布。"
        type="info"
        :closable="false"
      />
      <el-table :data="selected.versions"
        ><el-table-column
          prop="versionNo"
          label="版本"
          width="90"
        /><el-table-column
          prop="originalFilename"
          label="原文件"
          min-width="180"
        /><el-table-column label="发布 / 处理" min-width="140"
          ><template #default="{ row }"
            >{{ statuses[row.publicationStatus] }} /
            {{ statuses[row.processingStatus] }}</template
          ></el-table-column
        ><el-table-column label="有效期" min-width="230"
          ><template #default="{ row }"
            >{{ row.effectiveAt?.replace("T", " ") }}<br />至
            {{ row.expiresAt?.replace("T", " ") || "长期有效" }}<br /><small>{{
              row.searchable ? "当前有效" : "不用于当前查询"
            }}</small></template
          ></el-table-column
        ><el-table-column label="操作" min-width="210"
          ><template #default="{ row }"
            ><el-button
              link
              type="primary"
              :disabled="saving"
              @click="download(row)"
              >下载原文件</el-button
            ><el-button
              v-if="management && selected.manageable && row.publicationStatus === 'DRAFT' && ['PENDING', 'FAILED'].includes(row.processingStatus)"
              link type="primary" :disabled="saving" @click="openParse(row, true)"
              >{{ row.processingStatus === 'FAILED' ? '重试解析' : '开始解析' }}</el-button
            ><el-button
              v-if="management && selected.manageable" link :disabled="saving" @click="openParse(row)"
              >解析详情</el-button
            ><el-button
              v-if="management && selected.manageable && ['PARSED', 'READY'].includes(row.processingStatus) && ['DRAFT', 'PUBLISHED'].includes(row.publicationStatus)"
              link type="primary" :disabled="saving || indexing" @click="openIndex(row)"
              >索引管理</el-button
            ><el-button
              v-if="management && row.publicationStatus === 'DRAFT'"
              link
              :disabled="saving || row.processingStatus !== 'READY'"
              @click="act('publish', row)"
              >发布</el-button
            ><el-button
              v-if="management && selected.document.currentVersionId === row.id"
              link
              type="danger"
              :disabled="saving"
              @click="act('retire', row)"
              >停用</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <template v-if="management"
        ><h2 class="audit-title">操作记录</h2>
        <el-table :data="audits"
          ><el-table-column label="操作" min-width="140"
            ><template #default="{ row }">{{
              actions[row.action] || row.action
            }}</template></el-table-column
          ><el-table-column
            prop="actorId"
            label="操作人编号"
            width="110" /><el-table-column
            prop="versionId"
            label="版本编号"
            width="100" /><el-table-column
            prop="createdAt"
            label="时间"
            min-width="210" /></el-table
      ></template>
    </template>
  </section>
  <el-dialog v-model="indexDialog" title="制度索引管理" width="min(620px, 94vw)" @closed="closeIndex">
    <p v-if="indexVersion">版本 {{ indexVersion.versionNo }} · {{ { PROCESSING: '建立索引中', SUCCEEDED: '索引完成', FAILED: '索引失败' }[indexState?.status] || '尚未建立索引' }}</p>
    <el-alert v-if="indexError" :title="indexError" type="error" :closable="false" />
    <el-alert v-if="indexState?.status === 'FAILED'" :title="`索引失败（${indexState.errorCode}），请确认服务已启动后重试。`" type="error" :closable="false" />
    <p>将正文按条款切片并建立检索索引。草稿索引完成后，请核对正文再发布；重建期间该版本暂停检索。</p>
    <p v-if="indexState?.embeddingModel">Embedding：{{ indexState.embeddingModel }}</p>
    <el-button :disabled="indexing" @click="openIndex(indexVersion)">刷新索引状态</el-button>
    <el-button type="primary" :loading="indexing" :disabled="indexState?.status === 'PROCESSING'" @click="openIndex(indexVersion, true, indexState?.status === 'SUCCEEDED')">{{ indexState?.status === 'SUCCEEDED' ? '重建索引' : indexState?.status === 'FAILED' ? '重试索引' : '开始索引' }}</el-button>
  </el-dialog>
  <el-dialog v-model="parseDialog" title="解析结果与来源" width="min(860px, 94vw)" @closed="closeParse">
    <el-skeleton v-if="parseLoading" :rows="3" animated />
    <el-alert v-if="parseError" :title="parseError" type="error" :closable="false" />
    <template v-if="parseVersion && !parseLoading">
      <p>版本 {{ parseVersion.versionNo }}：{{ taskNames[parseTask?.status] || '尚未发起解析' }}<template v-if="parseTask">，已尝试 {{ parseTask.attempts }} 次</template></p>
      <el-alert v-if="parseTask?.status === 'FAILED'" :title="parseErrors[parseTask.errorCode] || `解析失败（${parseTask.errorCode}），请检查原文件后重试`" type="error" :closable="false" />
      <el-alert v-if="parseTask?.status === 'SUCCEEDED'" title="正文已提取，请核对原文件。向量索引完成前仍不能发布。低置信文字需人工核对。" type="info" :closable="false" />
      <el-button @click="openParse(parseVersion)">刷新解析状态</el-button>
      <el-button v-if="parseTask?.status === 'FAILED' && parseTask.attempts < 5" type="primary" @click="openParse({ ...parseVersion, processingStatus: 'FAILED' }, true)">重试解析任务</el-button>
      <article v-for="source in parseSources" :key="source.sourceIndex" class="parse-source">
        <h3>{{ source.location }}<small v-if="source.sectionTitle"> · {{ source.sectionTitle }}</small></h3>
        <p class="muted">{{ { TEXT: '正文提取', OCR: '文字识别', DOCX: 'Word 正文' }[source.method] }}<template v-if="source.confidence !== null"> · 平均置信度 {{ Math.round(source.confidence * 100) }}%</template></p>
        <el-tag v-if="source.warnings.length" type="warning">{{ source.warnings.includes('LOW_CONFIDENCE') ? '请人工核对' : '此页未识别到文字' }}</el-tag>
        <pre>{{ source.text || '（无文字）' }}</pre>
      </article>
    </template>
  </el-dialog>
  <el-dialog
    v-model="dialog"
    :title="
      mode === 'create'
        ? '上传新制度'
        : mode === 'version'
          ? '上传新版本'
          : '编辑制度范围'
    "
    width="min(620px, 94vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
  >
    <el-alert
      v-if="formError"
      :title="formError"
      type="error"
      :closable="false"
      role="alert"
    />
    <el-form label-position="top" @submit.prevent="save"
      ><template v-if="mode !== 'version'"
        ><el-form-item label="制度名称"
          ><el-input
            v-model="form.title"
            aria-label="制度名称"
            maxlength="255"
            :disabled="saving" /></el-form-item
        ><el-form-item label="可读角色"
          ><el-checkbox-group v-model="form.roles" :disabled="saving"
            ><el-checkbox
              v-for="(label, role) in roleNames"
              :key="role"
              :value="role"
              >{{ label }}</el-checkbox
            ></el-checkbox-group
          ></el-form-item
        ><el-form-item label="部门编号（可选，以英文逗号分隔）"
          ><el-input
            v-model="form.departments"
            aria-label="部门编号"
            placeholder="留空表示不限制部门；指定后仅允许所列部门"
            :disabled="saving" /></el-form-item
      ></template>
      <template v-if="mode !== 'scope'"
        ><el-form-item label="版本号"
          ><el-input
            v-model="form.versionNo"
            aria-label="版本号"
            placeholder="例如 v1.0，仅字母、数字、点、下划线和连字符"
            maxlength="32"
            :disabled="saving" /></el-form-item
        ><el-form-item label="生效时间（北京时间）"
          ><el-date-picker
            v-model="form.effectiveAt"
            aria-label="生效时间"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :disabled="saving" /></el-form-item
        ><el-form-item label="失效时间（可选，北京时间）"
          ><el-date-picker
            v-model="form.expiresAt"
            aria-label="失效时间"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :disabled="saving" /></el-form-item
        ><el-form-item label="原文件"
          ><input
            :key="mode + ':' + dialog"
            aria-label="原文件"
            type="file"
            accept=".pdf,.docx,.png,.jpg,.jpeg"
            :disabled="saving"
            @change="pickFile"
        /></el-form-item>
        <p class="muted">
          支持 PDF、DOCX、PNG、JPG 和 JPEG，最多 10 MiB。旧版 DOC 请先转换为
          DOCX。
        </p></template
      >
      <div class="dialog-actions">
        <el-button :disabled="saving" @click="dialog = false">取消</el-button
        ><el-button type="primary" native-type="submit" :loading="saving"
          >保存</el-button
        >
      </div>
    </el-form>
  </el-dialog>
</template>
<style scoped>
.panel-toolbar {
  gap: 14px;
  flex-wrap: wrap;
}
.panel-toolbar > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.panel-toolbar .el-button + .el-button {
  margin-left: 0;
}
.audit-title {
  margin-top: 32px;
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.el-alert {
  margin-bottom: 18px;
}
input[type="file"] {
  max-width: 100%;
}
.parse-source { margin-top: 24px; border-top: 1px solid var(--el-border-color); padding-top: 12px; }
.parse-source pre { white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.8; }
</style>
