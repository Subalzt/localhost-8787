// The phone as the laptop's webcam: a Windows 11 virtual camera ("Localhost 8787 Phone Camera").
//
// Windows' Frame Server (a service) loads this DLL as the camera's media source when an app opens
// the camera, and asks it for pictures. The pictures come from the laptop helper (blazeit-pc.bat),
// which decodes the phone's camera stream and leaves each picture, NV12 1280x720, in shared memory
// made here ("Global\Localhost8787Camera", open to the signed-in user). With no helper feeding it,
// the camera shows a dark grey picture with a slow pulse, so apps never hang on it.
//
// Exports: DllGetClassObject / DllCanUnloadNow / DllRegisterServer / DllUnregisterServer (COM),
// and Install / Uninstall (rundll32-style), which add or remove the camera through
// MFCreateVirtualCamera (Windows 11) for the user who runs it.

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mfvirtualcamera.h>
#include <ks.h>
#include <ksmedia.h>
#include <sddl.h>
#include <olectl.h>
#include <new>
#include <cmath>
#include <string>

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "mfsensorgroup.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "advapi32.lib")

// {6B1D9C3E-8E7A-4C55-9A61-8787C0FFEE01}
static const CLSID CLSID_PhoneCam = { 0x6b1d9c3e, 0x8e7a, 0x4c55, { 0x9a, 0x61, 0x87, 0x87, 0xc0, 0xff, 0xee, 0x01 } };
static const wchar_t* CLSID_TEXT = L"{6B1D9C3E-8E7A-4C55-9A61-8787C0FFEE01}";
static const wchar_t* FRIENDLY = L"Localhost 8787 Phone Camera";

static const UINT32 W = 1280, H = 720, FPS = 30;
static const DWORD FRAME_BYTES = W * H * 3 / 2;

// The shared picture: this header, then one NV12 picture. The helper writes seq last (odd while it
// writes, even once whole); [wanted] is 1 while an app is watching, for the helper to start the phone.
#pragma pack(push, 1)
struct Shared {
    UINT32 magic;      // 'L87C'
    UINT32 width, height;
    volatile LONG seq;
    volatile LONG wanted;
    volatile LONGLONG wroteAt; // GetTickCount64 of the last picture
    // GetTickCount64 of the last picture an app asked for: the helper feeds only while this is fresh,
    // so a camera left "wanted" (an app that died, a service that kept it) never keeps the phone's camera on.
    volatile LONGLONG askedAt;
    BYTE pad[64 - 4 * 5 - 8 - 8];
};
#pragma pack(pop)
static const UINT32 MAGIC = 0x4337384C;

// IKsControl (ksproxy.h's), which the camera service asks a source for: declared here, as that header drags DirectShow in.
MIDL_INTERFACE("28F54685-06FD-11D2-B27A-00A0C9223196")
IKsControl : public IUnknown {
public:
    virtual HRESULT STDMETHODCALLTYPE KsProperty(PKSPROPERTY p, ULONG pl, LPVOID d, ULONG dl, ULONG* r) = 0;
    virtual HRESULT STDMETHODCALLTYPE KsMethod(PKSMETHOD m, ULONG ml, LPVOID d, ULONG dl, ULONG* r) = 0;
    virtual HRESULT STDMETHODCALLTYPE KsEvent(PKSEVENT e, ULONG el, LPVOID d, ULONG dl, ULONG* r) = 0;
};

static HMODULE g_module = nullptr;
static volatile LONG g_objects = 0, g_locks = 0;

// ---------------------------------------------------------------------------------- shared memory

class Feed {
public:
    HANDLE map = nullptr;
    Shared* view = nullptr;
    void Open() {
        if (view) return;
        // Open to the signed-in user (the helper) and to the system: the Frame Server runs as a service.
        PSECURITY_DESCRIPTOR sd = nullptr;
        SECURITY_ATTRIBUTES sa = { sizeof(sa), nullptr, FALSE };
        if (ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:(A;;GA;;;SY)(A;;GA;;;LS)(A;;GA;;;IU)(A;;GA;;;BA)", SDDL_REVISION_1, &sd, nullptr))
            sa.lpSecurityDescriptor = sd;
        DWORD size = sizeof(Shared) + FRAME_BYTES;
        map = CreateFileMappingW(INVALID_HANDLE_VALUE, &sa, PAGE_READWRITE, 0, size, L"Global\\Localhost8787Camera");
        if (!map) map = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, L"Global\\Localhost8787Camera");
        if (sd) LocalFree(sd);
        if (!map) return;
        view = (Shared*)MapViewOfFile(map, FILE_MAP_ALL_ACCESS, 0, 0, size);
        if (view && view->magic != MAGIC) { view->magic = MAGIC; view->width = W; view->height = H; }
    }
    void Close() {
        if (view) { InterlockedExchange(&view->wanted, 0); UnmapViewOfFile(view); view = nullptr; }
        if (map) { CloseHandle(map); map = nullptr; }
    }
    void Want(bool on) { if (view) InterlockedExchange(&view->wanted, on ? 1 : 0); }
    void Asked() { if (view) view->askedAt = (LONGLONG)GetTickCount64(); }
    // The latest whole picture into [dst]; false (a placeholder instead) when the helper has not fed one lately.
    bool Copy(BYTE* dst) {
        if (!view) return false;
        if (GetTickCount64() - (ULONGLONG)view->wroteAt > 3000) return false;
        for (int tries = 0; tries < 3; tries++) {
            LONG a = view->seq;
            if (a & 1) { Sleep(2); continue; }
            memcpy(dst, (BYTE*)(view + 1), FRAME_BYTES);
            if (view->seq == a) return true;
        }
        return false;
    }
};

static void Placeholder(BYTE* dst, ULONGLONG t) {
    // Dark grey, breathing slowly, so it is plain the camera works and waits for the phone.
    BYTE y = (BYTE)(40 + 10 * (1 + sin((double)(t % 4000) / 4000.0 * 6.2831853)));
    memset(dst, y, W * H);
    memset(dst + W * H, 128, W * H / 2);
}

// ---------------------------------------------------------------------------------- the stream

class Source;

class Stream : public IMFMediaStream2 {
    volatile LONG ref = 1;
    CRITICAL_SECTION cs;
    IMFMediaEventQueue* queue = nullptr;
    IMFStreamDescriptor* desc = nullptr;
    Source* source; // not counted: the source owns the stream
    MF_STREAM_STATE state = MF_STREAM_STATE_STOPPED;
    LONGLONG start = 0;
    LONGLONG count = 0;
    BYTE* frame = nullptr;
    LONGLONG lastAt = 0;
public:
    Feed* feed;
    bool shut = false;
    Stream(Source* s, Feed* f) : source(s), feed(f) { InitializeCriticalSection(&cs); InterlockedIncrement(&g_objects); }
    ~Stream() { if (frame) free(frame); if (queue) queue->Release(); if (desc) desc->Release(); DeleteCriticalSection(&cs); InterlockedDecrement(&g_objects); }

    HRESULT Init() {
        HRESULT hr = MFCreateEventQueue(&queue);
        if (FAILED(hr)) return hr;
        IMFMediaType* t = nullptr;
        hr = MFCreateMediaType(&t);
        if (FAILED(hr)) return hr;
        t->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        t->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
        MFSetAttributeSize(t, MF_MT_FRAME_SIZE, W, H);
        MFSetAttributeRatio(t, MF_MT_FRAME_RATE, FPS, 1);
        MFSetAttributeRatio(t, MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        t->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        t->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
        t->SetUINT32(MF_MT_DEFAULT_STRIDE, W);
        t->SetUINT32(MF_MT_SAMPLE_SIZE, FRAME_BYTES);
        t->SetUINT32(MF_MT_AVG_BITRATE, FRAME_BYTES * 8 * FPS);
        IMFMediaType* types[] = { t };
        hr = MFCreateStreamDescriptor(0, 1, types, &desc);
        if (SUCCEEDED(hr)) {
            IMFMediaTypeHandler* h = nullptr;
            if (SUCCEEDED(desc->GetMediaTypeHandler(&h))) { h->SetCurrentMediaType(t); h->Release(); }
            desc->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY, PINNAME_VIDEO_CAPTURE);
            desc->SetUINT32(MF_DEVICESTREAM_STREAM_ID, 0);
            desc->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED, 1);
            desc->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES, MFFrameSourceTypes_Color);
        }
        t->Release();
        return hr;
    }
    IMFStreamDescriptor* Desc() { return desc; }

    HRESULT Begin(bool fromStop) {
        EnterCriticalSection(&cs);
        state = MF_STREAM_STATE_RUNNING;
        if (fromStop) { start = MFGetSystemTime(); count = 0; }
        HRESULT hr = queue->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, nullptr);
        LeaveCriticalSection(&cs);
        return hr;
    }
    HRESULT End() {
        EnterCriticalSection(&cs);
        state = MF_STREAM_STATE_STOPPED;
        HRESULT hr = queue ? queue->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr) : S_OK;
        LeaveCriticalSection(&cs);
        return hr;
    }
    void Shut() {
        EnterCriticalSection(&cs);
        shut = true;
        if (queue) queue->Shutdown();
        LeaveCriticalSection(&cs);
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == __uuidof(IMFMediaEventGenerator) || riid == __uuidof(IMFMediaStream) || riid == __uuidof(IMFMediaStream2))
        { *ppv = static_cast<IMFMediaStream2*>(this); AddRef(); return S_OK; }
        *ppv = nullptr; return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&ref); }
    STDMETHODIMP_(ULONG) Release() override { LONG r = InterlockedDecrement(&ref); if (!r) delete this; return r; }

    // IMFMediaEventGenerator
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* st) override { return shut ? MF_E_SHUTDOWN : queue->BeginGetEvent(cb, st); }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* r, IMFMediaEvent** e) override { return shut ? MF_E_SHUTDOWN : queue->EndGetEvent(r, e); }
    STDMETHODIMP GetEvent(DWORD f, IMFMediaEvent** e) override { return shut ? MF_E_SHUTDOWN : queue->GetEvent(f, e); }
    STDMETHODIMP QueueEvent(MediaEventType t, REFGUID g, HRESULT s, const PROPVARIANT* v) override { return shut ? MF_E_SHUTDOWN : queue->QueueEventParamVar(t, g, s, v); }

    // IMFMediaStream
    STDMETHODIMP GetMediaSource(IMFMediaSource** ppSource) override;
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** d) override {
        if (!d) return E_POINTER;
        if (shut) return MF_E_SHUTDOWN;
        *d = desc; desc->AddRef(); return S_OK;
    }
    STDMETHODIMP RequestSample(IUnknown* token) override {
        EnterCriticalSection(&cs);
        HRESULT hr = S_OK;
        IMFSample* sample = nullptr;
        IMFMediaBuffer* buf = nullptr;
        if (shut) hr = MF_E_SHUTDOWN;
        else if (state != MF_STREAM_STATE_RUNNING) hr = MF_E_MEDIA_SOURCE_WRONGSTATE;
        // No faster than the frame rate, however eagerly an app asks.
        // On a fixed schedule, so a long sleep (Windows' timer is coarse) is made up by the next.
        if (SUCCEEDED(hr)) {
            LONGLONG now = MFGetSystemTime(), period = 10000000LL / FPS;
            lastAt = (lastAt == 0 || now - lastAt > 4 * period) ? now : lastAt + period;
            if (lastAt > now) Sleep((DWORD)((lastAt - now) / 10000));
        }
        if (SUCCEEDED(hr)) hr = MFCreateSample(&sample);
        // A picture buffer (2D, NV12), as the camera service expects; filled row by row at its own pitch.
        if (SUCCEEDED(hr)) hr = MFCreate2DMediaBuffer(W, H, MFVideoFormat_NV12.Data1, FALSE, &buf);
        if (SUCCEEDED(hr)) {
            IMF2DBuffer2* b2 = nullptr;
            hr = buf->QueryInterface(IID_PPV_ARGS(&b2));
            BYTE* line = nullptr; BYTE* start = nullptr; LONG pitch = 0; DWORD len = 0;
            if (SUCCEEDED(hr)) hr = b2->Lock2DSize(MF2DBuffer_LockFlags_Write, &line, &pitch, &start, &len);
            if (SUCCEEDED(hr)) {
                feed->Asked();
                if (!frame) frame = (BYTE*)malloc(FRAME_BYTES);
                if (frame && !feed->Copy(frame)) Placeholder(frame, GetTickCount64());
                if (frame) {
                    for (UINT32 y = 0; y < H; y++) memcpy(line + (LONG)y * pitch, frame + y * W, W);
                    BYTE* uv = line + (LONG)H * pitch;
                    for (UINT32 y = 0; y < H / 2; y++) memcpy(uv + (LONG)y * pitch, frame + W * H + y * W, W);
                }
                b2->Unlock2D();
            }
            if (b2) b2->Release();
        }
        if (SUCCEEDED(hr)) hr = sample->AddBuffer(buf);
        if (SUCCEEDED(hr)) {
            // Each picture stamped with when it is handed over, a frame's length long.
            LONGLONG now = MFGetSystemTime();
            sample->SetSampleTime(now - start);
            sample->SetSampleDuration(10000000LL / FPS);
            count++;
            if (token) sample->SetUnknown(MFSampleExtension_Token, token);
            hr = queue->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, sample);
        }
        if (buf) buf->Release();
        if (sample) sample->Release();
        LeaveCriticalSection(&cs);
        return hr;
    }

    // IMFMediaStream2
    STDMETHODIMP SetStreamState(MF_STREAM_STATE v) override {
        if (v == state) return S_OK;
        if (v == MF_STREAM_STATE_RUNNING) return Begin(state == MF_STREAM_STATE_STOPPED);
        if (v == MF_STREAM_STATE_STOPPED) return End();
        EnterCriticalSection(&cs); state = v; LeaveCriticalSection(&cs);
        return S_OK;
    }
    STDMETHODIMP GetStreamState(MF_STREAM_STATE* v) override { if (!v) return E_POINTER; *v = state; return S_OK; }
};

// ---------------------------------------------------------------------------------- the source

class Source : public IMFMediaSourceEx, public IMFGetService, public IKsControl {
    volatile LONG ref = 1;
    CRITICAL_SECTION cs;
    IMFMediaEventQueue* queue = nullptr;
    IMFPresentationDescriptor* pd = nullptr;
    IMFAttributes* attrs = nullptr;
    Stream* stream = nullptr;
    Feed feed;
    bool shut = false;
    bool started = false;
public:
    Source() { InitializeCriticalSection(&cs); InterlockedIncrement(&g_objects); }
    ~Source() {
        if (stream) stream->Release();
        if (pd) pd->Release();
        if (attrs) attrs->Release();
        if (queue) queue->Release();
        feed.Close();
        DeleteCriticalSection(&cs);
        InterlockedDecrement(&g_objects);
    }

    HRESULT Init(IMFAttributes* from) {
        HRESULT hr = MFCreateEventQueue(&queue);
        if (SUCCEEDED(hr)) hr = MFCreateAttributes(&attrs, 4);
        if (SUCCEEDED(hr) && from) from->CopyAllItems(attrs);
        if (SUCCEEDED(hr)) {
            feed.Open();
            stream = new (std::nothrow) Stream(this, &feed);
            hr = stream ? stream->Init() : E_OUTOFMEMORY;
        }
        if (SUCCEEDED(hr)) {
            IMFStreamDescriptor* d = stream->Desc();
            hr = MFCreatePresentationDescriptor(1, &d, &pd);
        }
        if (SUCCEEDED(hr)) hr = pd->SelectStream(0);
        return hr;
    }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == __uuidof(IMFMediaEventGenerator) || riid == __uuidof(IMFMediaSource) || riid == __uuidof(IMFMediaSourceEx))
            *ppv = static_cast<IMFMediaSourceEx*>(this);
        else if (riid == __uuidof(IMFGetService)) *ppv = static_cast<IMFGetService*>(this);
        else if (riid == __uuidof(IKsControl)) *ppv = static_cast<IKsControl*>(this);
        else { *ppv = nullptr; return E_NOINTERFACE; }
        AddRef();
        return S_OK;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&ref); }
    STDMETHODIMP_(ULONG) Release() override { LONG r = InterlockedDecrement(&ref); if (!r) delete this; return r; }

    // IMFMediaEventGenerator
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb, IUnknown* st) override { return shut ? MF_E_SHUTDOWN : queue->BeginGetEvent(cb, st); }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* r, IMFMediaEvent** e) override { return shut ? MF_E_SHUTDOWN : queue->EndGetEvent(r, e); }
    STDMETHODIMP GetEvent(DWORD f, IMFMediaEvent** e) override { return shut ? MF_E_SHUTDOWN : queue->GetEvent(f, e); }
    STDMETHODIMP QueueEvent(MediaEventType t, REFGUID g, HRESULT s, const PROPVARIANT* v) override { return shut ? MF_E_SHUTDOWN : queue->QueueEventParamVar(t, g, s, v); }

    // IMFMediaSource
    STDMETHODIMP GetCharacteristics(DWORD* c) override { if (!c) return E_POINTER; if (shut) return MF_E_SHUTDOWN; *c = MFMEDIASOURCE_IS_LIVE; return S_OK; }
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** out) override {
        if (!out) return E_POINTER;
        if (shut) return MF_E_SHUTDOWN;
        return pd->Clone(out);
    }
    STDMETHODIMP Start(IMFPresentationDescriptor* given, const GUID* fmt, const PROPVARIANT* at) override {
        if (!given) return E_INVALIDARG;
        if (fmt && *fmt != GUID_NULL) return MF_E_UNSUPPORTED_TIME_FORMAT;
        EnterCriticalSection(&cs);
        HRESULT hr = shut ? MF_E_SHUTDOWN : S_OK;
        if (SUCCEEDED(hr)) {
            BOOL selected = FALSE;
            IMFStreamDescriptor* d = nullptr;
            hr = given->GetStreamDescriptorByIndex(0, &selected, &d);
            if (d) d->Release();
            if (SUCCEEDED(hr) && selected) {
                bool fresh = !started;
                started = true;
                feed.Want(true);
                PROPVARIANT v; PropVariantInit(&v); v.vt = VT_I8; v.hVal.QuadPart = MFGetSystemTime();
                // The stream first (new, or carried on), then the source.
                stream->AddRef();
                hr = queue->QueueEventParamUnk(fresh ? MENewStream : MEUpdatedStream, GUID_NULL, S_OK, static_cast<IMFMediaStream*>(stream));
                stream->Release();
                if (SUCCEEDED(hr)) hr = stream->Begin(fresh);
                if (SUCCEEDED(hr)) hr = queue->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, &v);
            }
        }
        LeaveCriticalSection(&cs);
        return hr;
    }
    STDMETHODIMP Stop() override {
        EnterCriticalSection(&cs);
        HRESULT hr = shut ? MF_E_SHUTDOWN : S_OK;
        if (SUCCEEDED(hr)) {
            started = false;
            feed.Want(false);
            hr = stream->End();
            if (SUCCEEDED(hr)) hr = queue->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
        }
        LeaveCriticalSection(&cs);
        return hr;
    }
    STDMETHODIMP Pause() override { return MF_E_INVALID_STATE_TRANSITION; }
    STDMETHODIMP Shutdown() override {
        EnterCriticalSection(&cs);
        if (!shut) {
            shut = true;
            feed.Want(false);
            if (stream) stream->Shut();
            if (queue) queue->Shutdown();
        }
        LeaveCriticalSection(&cs);
        return S_OK;
    }

    // IMFMediaSourceEx
    STDMETHODIMP GetSourceAttributes(IMFAttributes** a) override { if (!a) return E_POINTER; *a = attrs; attrs->AddRef(); return S_OK; }
    STDMETHODIMP GetStreamAttributes(DWORD id, IMFAttributes** a) override {
        if (!a) return E_POINTER;
        if (id != 0) return MF_E_INVALIDSTREAMNUMBER;
        return stream->Desc()->QueryInterface(IID_PPV_ARGS(a));
    }
    STDMETHODIMP SetD3DManager(IUnknown*) override { return S_OK; } // pictures in system memory

    // IMFGetService
    STDMETHODIMP GetService(REFGUID, REFIID, LPVOID*) override { return MF_E_UNSUPPORTED_SERVICE; }

    // IKsControl: no properties of its own (no exposure, no focus): a fixed camera.
    STDMETHODIMP KsProperty(PKSPROPERTY, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    STDMETHODIMP KsMethod(PKSMETHOD, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }
    STDMETHODIMP KsEvent(PKSEVENT, ULONG, LPVOID, ULONG, ULONG*) override { return HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND); }

    friend class Stream;
};

STDMETHODIMP Stream::GetMediaSource(IMFMediaSource** ppSource) {
    if (!ppSource) return E_POINTER;
    if (shut) return MF_E_SHUTDOWN;
    *ppSource = static_cast<IMFMediaSource*>(static_cast<IMFMediaSourceEx*>(source));
    (*ppSource)->AddRef();
    return S_OK;
}

// ---------------------------------------------------------------------------------- the activate

// What the Frame Server creates from the CLSID: an attribute store that makes the source when asked.
class Activate : public IMFActivate {
    volatile LONG ref = 1;
    IMFAttributes* a = nullptr;
    IMFMediaSource* made = nullptr;
public:
    Activate() { InterlockedIncrement(&g_objects); }
    ~Activate() { if (made) made->Release(); if (a) a->Release(); InterlockedDecrement(&g_objects); }
    HRESULT Init() { return MFCreateAttributes(&a, 4); }

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == __uuidof(IMFAttributes) || riid == __uuidof(IMFActivate)) { *ppv = static_cast<IMFActivate*>(this); AddRef(); return S_OK; }
        *ppv = nullptr; return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&ref); }
    STDMETHODIMP_(ULONG) Release() override { LONG r = InterlockedDecrement(&ref); if (!r) delete this; return r; }

    STDMETHODIMP ActivateObject(REFIID riid, void** ppv) override {
        if (!made) {
            Source* s = new (std::nothrow) Source();
            if (!s) return E_OUTOFMEMORY;
            HRESULT hr = s->Init(a);
            if (FAILED(hr)) { s->Release(); return hr; }
            made = static_cast<IMFMediaSource*>(static_cast<IMFMediaSourceEx*>(s));
        }
        return made->QueryInterface(riid, ppv);
    }
    STDMETHODIMP ShutdownObject() override { if (made) { made->Shutdown(); made->Release(); made = nullptr; } return S_OK; }
    STDMETHODIMP DetachObject() override { if (made) { made->Release(); made = nullptr; } return S_OK; }

    // IMFAttributes, all handed to the store.
    STDMETHODIMP GetItem(REFGUID k, PROPVARIANT* v) override { return a->GetItem(k, v); }
    STDMETHODIMP GetItemType(REFGUID k, MF_ATTRIBUTE_TYPE* t) override { return a->GetItemType(k, t); }
    STDMETHODIMP CompareItem(REFGUID k, REFPROPVARIANT v, BOOL* r) override { return a->CompareItem(k, v, r); }
    STDMETHODIMP Compare(IMFAttributes* t, MF_ATTRIBUTES_MATCH_TYPE m, BOOL* r) override { return a->Compare(t, m, r); }
    STDMETHODIMP GetUINT32(REFGUID k, UINT32* v) override { return a->GetUINT32(k, v); }
    STDMETHODIMP GetUINT64(REFGUID k, UINT64* v) override { return a->GetUINT64(k, v); }
    STDMETHODIMP GetDouble(REFGUID k, double* v) override { return a->GetDouble(k, v); }
    STDMETHODIMP GetGUID(REFGUID k, GUID* v) override { return a->GetGUID(k, v); }
    STDMETHODIMP GetStringLength(REFGUID k, UINT32* n) override { return a->GetStringLength(k, n); }
    STDMETHODIMP GetString(REFGUID k, LPWSTR v, UINT32 n, UINT32* l) override { return a->GetString(k, v, n, l); }
    STDMETHODIMP GetAllocatedString(REFGUID k, LPWSTR* v, UINT32* l) override { return a->GetAllocatedString(k, v, l); }
    STDMETHODIMP GetBlobSize(REFGUID k, UINT32* n) override { return a->GetBlobSize(k, n); }
    STDMETHODIMP GetBlob(REFGUID k, UINT8* b, UINT32 n, UINT32* l) override { return a->GetBlob(k, b, n, l); }
    STDMETHODIMP GetAllocatedBlob(REFGUID k, UINT8** b, UINT32* n) override { return a->GetAllocatedBlob(k, b, n); }
    STDMETHODIMP GetUnknown(REFGUID k, REFIID r, LPVOID* v) override { return a->GetUnknown(k, r, v); }
    STDMETHODIMP SetItem(REFGUID k, REFPROPVARIANT v) override { return a->SetItem(k, v); }
    STDMETHODIMP DeleteItem(REFGUID k) override { return a->DeleteItem(k); }
    STDMETHODIMP DeleteAllItems() override { return a->DeleteAllItems(); }
    STDMETHODIMP SetUINT32(REFGUID k, UINT32 v) override { return a->SetUINT32(k, v); }
    STDMETHODIMP SetUINT64(REFGUID k, UINT64 v) override { return a->SetUINT64(k, v); }
    STDMETHODIMP SetDouble(REFGUID k, double v) override { return a->SetDouble(k, v); }
    STDMETHODIMP SetGUID(REFGUID k, REFGUID v) override { return a->SetGUID(k, v); }
    STDMETHODIMP SetString(REFGUID k, LPCWSTR v) override { return a->SetString(k, v); }
    STDMETHODIMP SetBlob(REFGUID k, const UINT8* b, UINT32 n) override { return a->SetBlob(k, b, n); }
    STDMETHODIMP SetUnknown(REFGUID k, IUnknown* v) override { return a->SetUnknown(k, v); }
    STDMETHODIMP LockStore() override { return a->LockStore(); }
    STDMETHODIMP UnlockStore() override { return a->UnlockStore(); }
    STDMETHODIMP GetCount(UINT32* n) override { return a->GetCount(n); }
    STDMETHODIMP GetItemByIndex(UINT32 i, GUID* k, PROPVARIANT* v) override { return a->GetItemByIndex(i, k, v); }
    STDMETHODIMP CopyAllItems(IMFAttributes* d) override { return a->CopyAllItems(d); }
};

// ---------------------------------------------------------------------------------- COM

class Factory : public IClassFactory {
public:
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) { *ppv = static_cast<IClassFactory*>(this); return S_OK; }
        *ppv = nullptr; return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return 2; }
    STDMETHODIMP_(ULONG) Release() override { return 1; }
    STDMETHODIMP CreateInstance(IUnknown* outer, REFIID riid, void** ppv) override {
        if (outer) return CLASS_E_NOAGGREGATION;
        Activate* a = new (std::nothrow) Activate();
        if (!a) return E_OUTOFMEMORY;
        HRESULT hr = a->Init();
        if (SUCCEEDED(hr)) hr = a->QueryInterface(riid, ppv);
        a->Release();
        return hr;
    }
    STDMETHODIMP LockServer(BOOL lock) override { if (lock) InterlockedIncrement(&g_locks); else InterlockedDecrement(&g_locks); return S_OK; }
};
static Factory g_factory;

BOOL APIENTRY DllMain(HMODULE m, DWORD why, LPVOID) {
    if (why == DLL_PROCESS_ATTACH) { g_module = m; DisableThreadLibraryCalls(m); }
    return TRUE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, LPVOID* ppv) {
    if (clsid != CLSID_PhoneCam) return CLASS_E_CLASSNOTAVAILABLE;
    return g_factory.QueryInterface(riid, ppv);
}

STDAPI DllCanUnloadNow() { return (g_objects == 0 && g_locks == 0) ? S_OK : S_FALSE; }

// In HKLM, so the Frame Server (a service, another account) finds it: needs an administrator.
STDAPI DllRegisterServer() {
    wchar_t path[MAX_PATH];
    GetModuleFileNameW(g_module, path, MAX_PATH);
    std::wstring key = std::wstring(L"Software\\Classes\\CLSID\\") + CLSID_TEXT;
    HKEY k;
    if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, key.c_str(), 0, nullptr, 0, KEY_WRITE, nullptr, &k, nullptr) != ERROR_SUCCESS) return SELFREG_E_CLASS;
    RegSetValueExW(k, nullptr, 0, REG_SZ, (const BYTE*)FRIENDLY, (DWORD)((wcslen(FRIENDLY) + 1) * 2));
    RegCloseKey(k);
    if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, (key + L"\\InprocServer32").c_str(), 0, nullptr, 0, KEY_WRITE, nullptr, &k, nullptr) != ERROR_SUCCESS) return SELFREG_E_CLASS;
    RegSetValueExW(k, nullptr, 0, REG_SZ, (const BYTE*)path, (DWORD)((wcslen(path) + 1) * 2));
    const wchar_t* both = L"Both";
    RegSetValueExW(k, L"ThreadingModel", 0, REG_SZ, (const BYTE*)both, (DWORD)((wcslen(both) + 1) * 2));
    RegCloseKey(k);
    return S_OK;
}

STDAPI DllUnregisterServer() {
    std::wstring key = std::wstring(L"Software\\Classes\\CLSID\\") + CLSID_TEXT;
    RegDeleteTreeW(HKEY_LOCAL_MACHINE, key.c_str());
    return S_OK;
}

// ---------------------------------------------------------------------------------- the camera

static HRESULT Make(IMFVirtualCamera** cam) {
    return MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, MFVirtualCameraLifetime_System,
        MFVirtualCameraAccess_CurrentUser, FRIENDLY, CLSID_TEXT, nullptr, 0, cam);
}

// rundll32 vcam.dll,Install: the camera added for this user, kept across restarts. Exit code via the log file.
extern "C" __declspec(dllexport) void CALLBACK Install(HWND, HINSTANCE, LPSTR, int) {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION);
    IMFVirtualCamera* cam = nullptr;
    hr = Make(&cam);
    if (SUCCEEDED(hr)) hr = cam->Start(nullptr);
    if (cam) cam->Release();
    MFShutdown();
    CoUninitialize();
    ExitProcess((UINT)hr);
}

extern "C" __declspec(dllexport) void CALLBACK Uninstall(HWND, HINSTANCE, LPSTR, int) {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION);
    IMFVirtualCamera* cam = nullptr;
    hr = Make(&cam);
    if (SUCCEEDED(hr)) hr = cam->Remove();
    if (cam) cam->Release();
    MFShutdown();
    CoUninitialize();
    ExitProcess((UINT)hr);
}
