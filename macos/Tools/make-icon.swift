import AppKit

// Renders the app icon (1024×1024 PNG) used to build AppIcon.icns.
let size: CGFloat = 1024
let image = NSImage(size: NSSize(width: size, height: size))
image.lockFocus()

let bgRect = NSRect(x: 44, y: 44, width: size - 88, height: size - 88)
let bg = NSBezierPath(roundedRect: bgRect, xRadius: 210, yRadius: 210)
NSGradient(colors: [
    NSColor(srgbRed: 0.99, green: 0.97, blue: 0.93, alpha: 1),
    NSColor(srgbRed: 0.91, green: 0.85, blue: 0.74, alpha: 1),
])?.draw(in: bg, angle: -90)
NSColor(srgbRed: 0.55, green: 0.42, blue: 0.22, alpha: 1).setStroke()
bg.lineWidth = 10
bg.stroke()

// pages
let leftPage = NSBezierPath()
leftPage.move(to: NSPoint(x: 210, y: 300))
leftPage.curve(to: NSPoint(x: 512, y: 330), controlPoint1: NSPoint(x: 320, y: 320), controlPoint2: NSPoint(x: 430, y: 330))
leftPage.line(to: NSPoint(x: 512, y: 760))
leftPage.curve(to: NSPoint(x: 210, y: 730), controlPoint1: NSPoint(x: 430, y: 760), controlPoint2: NSPoint(x: 320, y: 750))
leftPage.close()

let rightPage = NSBezierPath()
rightPage.move(to: NSPoint(x: 512, y: 330))
rightPage.curve(to: NSPoint(x: 814, y: 300), controlPoint1: NSPoint(x: 594, y: 330), controlPoint2: NSPoint(x: 704, y: 320))
rightPage.line(to: NSPoint(x: 814, y: 730))
rightPage.curve(to: NSPoint(x: 512, y: 760), controlPoint1: NSPoint(x: 704, y: 750), controlPoint2: NSPoint(x: 594, y: 760))
rightPage.close()

NSColor.white.setFill()
leftPage.fill()
rightPage.fill()

NSColor(srgbRed: 0.80, green: 0.76, blue: 0.68, alpha: 1).setStroke()
leftPage.lineWidth = 3
rightPage.lineWidth = 3
leftPage.stroke()
rightPage.stroke()

// text lines
NSColor(srgbRed: 0.42, green: 0.36, blue: 0.29, alpha: 0.55).setFill()
for i in 0..<7 {
    let y = 700 - CGFloat(i) * 52
    let inset = CGFloat(i % 3 == 2 ? 28 : 0)
    NSRect(x: 268 + inset, y: y, width: 210 - inset, height: 16).fill()
    NSRect(x: 546, y: y, width: 210 - inset, height: 16).fill()
}

// spine and bookmark
NSColor(srgbRed: 0.60, green: 0.46, blue: 0.25, alpha: 1).setFill()
NSRect(x: 500, y: 300, width: 24, height: 470).fill()
NSColor(srgbRed: 0.72, green: 0.33, blue: 0.24, alpha: 1).setFill()
let ribbon = NSBezierPath()
ribbon.move(to: NSPoint(x: 700, y: 706))
ribbon.line(to: NSPoint(x: 770, y: 706))
ribbon.line(to: NSPoint(x: 770, y: 560))
ribbon.line(to: NSPoint(x: 735, y: 596))
ribbon.line(to: NSPoint(x: 700, y: 560))
ribbon.close()
ribbon.fill()

image.unlockFocus()

guard let tiff = image.tiffRepresentation,
      let rep = NSBitmapImageRep(data: tiff),
      let png = rep.representation(using: .png, properties: [:]) else {
    print("icon render failed")
    exit(1)
}
let out = URL(fileURLWithPath: "Resources/icon-1024.png")
try! png.write(to: out)
print("wrote \(out.path)")
