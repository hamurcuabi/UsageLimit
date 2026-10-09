# Kullanım Limiti

Günlük telefon kullanımını sınırlamak için kişisel Android uygulaması.

- Uygulamaları bugünkü kullanım süresine göre sıralar.
- Her uygulama için son 7 günün günlük ortalamasını ve açılış başına ortalama süreyi gösterir.
- Tek bir **varsayılan günlük limit** tüm uygulamalara uygulanır; istediğin uygulamaya özel limit verebilir veya sınırsız yapabilirsin.
- Limitin %80'inde bildirim, limit dolunca tam ekran uyarı çıkar. Günde uygulama başına 2 kez "5 dk daha" hakkı vardır.

## APK

`main` dalına her push'ta GitHub Actions APK derler ve [Releases](../../releases/latest) sayfasına koyar. Her derleme aynı anahtarla imzalandığı için yeni sürüm eskisinin üzerine kurulur.

## Gerekli izinler

| İzin | Neden |
| --- | --- |
| Kullanım erişimi | Uygulama sürelerini okumak için (zorunlu) |
| Diğer uygulamaların üzerinde göster | Limit dolunca tam ekran uyarı |
| Bildirimler | Limit dolmadan önce uyarı |
| Pil optimizasyonu muafiyeti | İzleme servisinin arka planda kapanmaması |

Veriler cihazdan çıkmaz; uygulamanın internet izni yoktur.
