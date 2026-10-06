// Reads a few pictures from "Localhost 8787 Phone Camera" through Media Foundation, as an app would,
// and prints what each step returns and each picture's mean brightness: a check without looking.
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <stdio.h>
#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mf.lib")
#pragma comment(lib, "mfreadwrite.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "ole32.lib")

int wmain(int argc, wchar_t** argv) {
    int frames = argc > 1 ? _wtoi(argv[1]) : 30;
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    MFStartup(MF_VERSION);
    IMFAttributes* a = nullptr;
    MFCreateAttributes(&a, 1);
    a->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
    IMFActivate** devs = nullptr; UINT32 n = 0;
    HRESULT hr = MFEnumDeviceSources(a, &devs, &n);
    printf("enum hr=%08lx count=%u\n", hr, n);
    IMFActivate* mine = nullptr;
    // "local": the DLL beside this program, made straight (no camera service), to try a build without installing it.
    if (argc > 2 && wcscmp(argv[2], L"local") == 0) {
        HMODULE m = LoadLibraryW(L"vcam.dll");
        typedef HRESULT(STDAPICALLTYPE* GetCO)(REFCLSID, REFIID, LPVOID*);
        GetCO get = (GetCO)GetProcAddress(m, "DllGetClassObject");
        const CLSID c = { 0x6b1d9c3e, 0x8e7a, 0x4c55, { 0x9a, 0x61, 0x87, 0x87, 0xc0, 0xff, 0xee, 0x01 } };
        IClassFactory* f = nullptr;
        hr = get ? get(c, IID_PPV_ARGS(&f)) : E_FAIL;
        if (SUCCEEDED(hr)) hr = f->CreateInstance(nullptr, IID_PPV_ARGS(&mine));
        printf("local hr=%08lx\n", hr);
    }
    for (UINT32 i = 0; i < n; i++) {
        WCHAR* name = nullptr; UINT32 len = 0;
        devs[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &len);
        wprintf(L"  %s\n", name);
        if (!mine && name && wcsstr(name, L"Localhost 8787")) mine = devs[i];
        CoTaskMemFree(name);
    }
    if (!mine) { printf("not found\n"); return 1; }
    IMFMediaSource* src = nullptr;
    hr = mine->ActivateObject(IID_PPV_ARGS(&src));
    printf("activate hr=%08lx\n", hr);
    if (FAILED(hr)) return 2;
    IMFSourceReader* r = nullptr;
    hr = MFCreateSourceReaderFromMediaSource(src, nullptr, &r);
    printf("reader hr=%08lx\n", hr);
    if (FAILED(hr)) return 3;
    IMFMediaType* t = nullptr;
    if (SUCCEEDED(r->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, &t))) {
        UINT32 w = 0, h = 0; MFGetAttributeSize(t, MF_MT_FRAME_SIZE, &w, &h);
        GUID sub; t->GetGUID(MF_MT_SUBTYPE, &sub);
        printf("type %ux%u subtype %08lx\n", w, h, sub.Data1);
        t->Release();
    }
    DWORD t0 = GetTickCount();
    for (int i = 0; i < frames; i++) {
        DWORD idx, flags; LONGLONG ts; IMFSample* s = nullptr;
        hr = r->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, &idx, &flags, &ts, &s);
        if (FAILED(hr)) { printf("read %d hr=%08lx\n", i, hr); break; }
        if (!s) { printf("read %d: no sample, flags=%lx\n", i, flags); continue; }
        IMFMediaBuffer* b = nullptr; s->ConvertToContiguousBuffer(&b);
        BYTE* p; DWORD max, cur; b->Lock(&p, &max, &cur);
        double sum = 0; DWORD ny = 1280 * 720 < cur ? 1280 * 720 : cur;
        for (DWORD k = 0; k < ny; k += 97) sum += p[k];
        if (i % 10 == 0 || i == frames - 1) printf("frame %d bytes=%lu mean Y=%.1f t=%.2fs\n", i, cur, sum / (ny / 97 + 1), (GetTickCount() - t0) / 1000.0);
        b->Unlock(); b->Release(); s->Release();
    }
    printf("%d frames in %.2f s\n", frames, (GetTickCount() - t0) / 1000.0);
    r->Release(); src->Shutdown(); src->Release();
    return 0;
}
