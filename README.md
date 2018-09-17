# nexradNow-android

This app downloads a Nexrad weather product for your local area and displays it on your Android device. Currently, it uses
the GPS location of your device to find several nearby Nexrad radar stations, downloads recent weather products directly from
the NWS radar operations center, and displays them on your device. A simple state map overlay is generated as a visual reference.

The display is intended to show the products for a broad geographic area (several states), rather than for a single
very local area. It aggregates the data from the various Nexrad stations to achieve this coverage. It can search
for Nexrad stations within up to 500 miles of your current location.

The products currently supported include the base & composite reflectivities at 120 and 240 nm ranges.
I will add more proucts 

You can choose your location from either the device's current GPS location, or you can center on an existing Nexrad site.
If you are feeling adventurous, you can enter a city/state or place name, and the app will use Android's geocoder to
figure out a physical location.

![Image](/screenshots/screenshot1.png)

![Image](/screenshots/screenshot2.png)

The app continues to evolve.

TO DOs: 

* all radar products 

* most features from pykl3, radarscope, radarx  

* animation of weather products when possible

Your suggestions welcome!
